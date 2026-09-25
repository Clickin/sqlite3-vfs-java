package io.github.clickin.sqlitevfs.adapter;

import io.github.clickin.sqlitevfs.NioVfs;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.github.clickin.sqlitevfs.SqliteCodes.*;

/**
 * Instance-owned handles and checked guest offsets for a SQLite VFS trampoline.
 * The engine serializes calls into this object; unrelated engines share no buffers.
 */
public final class VfsBridge {
    /** Used by crash/failure acceptance programs; absent on normal connections. */
    @FunctionalInterface
    public interface Observer {
        void onIo(String operation, int flags, String path, long offset, int amount, int result);
    }

    private record OpenFile(NioVfs.File file, int flags, String path) {}
    private static final class Slot {
        int generation;
        int nextFree;
        OpenFile file;
    }
    private static final int MAX_PATH_BYTES = 32768;
    private final NioVfs vfs;
    private final EngineMemory memory;
    private final Observer observer;
    private final List<Slot> slots = new ArrayList<>();
    private final byte[] bytes = new byte[65536];
    private final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    private int freeSlot;

    public VfsBridge(NioVfs vfs, EngineMemory memory) {
        this(vfs, memory, null);
    }

    public VfsBridge(NioVfs vfs, EngineMemory memory, Observer observer) {
        this.vfs = Objects.requireNonNull(vfs);
        this.memory = Objects.requireNonNull(memory);
        this.observer = observer;
        slots.add(null); // Handle zero is never a file.
    }

    public int open(int nameAddress, int flags, int outHandle, int outFlags) {
        if (!memory.contains(outHandle, 4) || !memory.contains(outFlags, 4)) return SQLITE_MISUSE;
        memory.writeInt(outHandle, 0);
        memory.writeInt(outFlags, 0);
        if (freeSlot == 0 && slots.size() == 65536) return SQLITE_CANTOPEN;
        String name;
        try {
            name = nameAddress == 0 ? null : filename(nameAddress);
        } catch (IllegalArgumentException failure) {
            return SQLITE_CANTOPEN;
        }
        NioVfs.OpenResult opened = vfs.open(name, flags);
        if (opened.code() != SQLITE_OK) return opened.code();
        int handle = store(new OpenFile(opened.file(), opened.flags(), name));
        memory.writeInt(outHandle, handle);
        memory.writeInt(outFlags, opened.flags());
        return SQLITE_OK;
    }

    public int close(int handle) {
        OpenFile entry = lookup(handle);
        if (entry == null) return SQLITE_MISUSE;
        if (memory.sharedMemorySupported()) memory.unmapShared(handle);
        int result = entry.file().close();
        if (result == SQLITE_OK) remove(handle);
        return result;
    }

    public int read(int handle, int address, int amount, long offset) {
        OpenFile entry = lookup(handle);
        if (!validIo(entry, address, amount, offset)) return SQLITE_MISUSE;
        notify(entry, "before-read", offset, amount, SQLITE_OK);
        int done = 0;
        int result = SQLITE_OK;
        while (done < amount) {
            int count = Math.min(bytes.length, amount - done);
            buffer.clear().limit(count);
            result = entry.file().read(buffer, offset + done);
            // On real read error only bytes actually read may replace the guest buffer.
            int transferred = result == SQLITE_OK || result == SQLITE_IOERR_SHORT_READ ? count : buffer.position();
            memory.write(address + done, bytes, 0, transferred);
            done += transferred;
            if (result == SQLITE_IOERR_SHORT_READ) {
                Arrays.fill(bytes, (byte) 0);
                while (done < amount) {
                    count = Math.min(bytes.length, amount - done);
                    memory.write(address + done, bytes, 0, count);
                    done += count;
                }
                break;
            }
            if (result != SQLITE_OK) break;
        }
        notify(entry, "after-read", offset, amount, result);
        return result;
    }

    public int write(int handle, int address, int amount, long offset) {
        OpenFile entry = lookup(handle);
        if (!validIo(entry, address, amount, offset)) return SQLITE_MISUSE;
        notify(entry, "before-write", offset, amount, SQLITE_OK);
        int result = SQLITE_OK;
        for (int done = 0; done < amount;) {
            int count = Math.min(bytes.length, amount - done);
            memory.read(address + done, bytes, 0, count);
            buffer.clear().limit(count);
            result = entry.file().write(buffer, offset + done);
            if (result != SQLITE_OK) break;
            done += count;
        }
        notify(entry, "after-write", offset, amount, result);
        return result;
    }

    private boolean validIo(OpenFile file, int address, int amount, long offset) {
        return file != null && memory.contains(address, amount)
                && offset >= 0 && offset <= Long.MAX_VALUE - amount;
    }

    public int truncate(int handle, long size) {
        OpenFile entry = lookup(handle);
        if (entry == null || size < 0) return SQLITE_MISUSE;
        return entry.file().truncate(size);
    }

    public int sync(int handle, int flags) {
        OpenFile entry = lookup(handle);
        if (entry == null) return SQLITE_MISUSE;
        notify(entry, "before-sync", 0, flags, SQLITE_OK);
        int result = entry.file().sync(flags);
        notify(entry, "after-sync", 0, flags, result);
        return result;
    }

    public int size(int handle, int outAddress) {
        OpenFile entry = lookup(handle);
        if (entry == null || !memory.contains(outAddress, 8)) return SQLITE_MISUSE;
        NioVfs.LongResult result = entry.file().fileSize();
        if (result.code() == SQLITE_OK) memory.writeLong(outAddress, result.value());
        return result.code();
    }

    public int lock(int handle, int level) {
        OpenFile entry = lookup(handle);
        return entry == null ? SQLITE_MISUSE : entry.file().lock(level);
    }

    public int unlock(int handle, int level) {
        OpenFile entry = lookup(handle);
        return entry == null ? SQLITE_MISUSE : entry.file().unlock(level);
    }

    public int reserved(int handle, int outAddress) {
        OpenFile entry = lookup(handle);
        if (entry == null || !memory.contains(outAddress, 4)) return SQLITE_MISUSE;
        NioVfs.IntResult result = entry.file().checkReservedLock();
        if (result.code() == SQLITE_OK) memory.writeInt(outAddress, result.value());
        return result.code();
    }

    public int shmSupported() {
        return memory.sharedMemorySupported() && NioVfs.sharedMemorySupported() ? 1 : 0;
    }

    public int shmMap(int handle, int page, int pageSize, int extend, int address, int mappedOut) {
        if (!memory.contains(mappedOut, 4)) return SQLITE_MISUSE;
        memory.writeInt(mappedOut, 0);
        OpenFile entry = lookup(handle);
        if (entry == null || page < 0 || pageSize <= 0
                || address == 0 || (address & 7) != 0 || !memory.contains(address, pageSize)
                || ((long) mappedOut < (long) address + pageSize && (long) mappedOut + 4 > address)) {
            return SQLITE_MISUSE;
        }
        if (shmSupported() == 0) return SQLITE_IOERR_SHMMAP;
        NioVfs.ShmResult result = entry.file().shmMap(page, pageSize, extend != 0);
        if (result.region() != null && (result.code() == SQLITE_OK || result.code() == SQLITE_READONLY)) {
            try {
                memory.mapShared(handle, page, address, result.region());
            } catch (IllegalArgumentException failure) {
                return SQLITE_MISUSE;
            } catch (OutOfMemoryError failure) {
                return SQLITE_NOMEM;
            }
            memory.writeInt(mappedOut, 1);
        }
        return result.code();
    }

    public int shmLock(int handle, int offset, int count, int flags) {
        OpenFile entry = lookup(handle);
        return entry == null ? SQLITE_MISUSE : entry.file().shmLock(offset, count, flags);
    }

    public void shmBarrier(int handle) {
        OpenFile entry = lookup(handle);
        if (entry == null) throw new IllegalArgumentException("Invalid shared-memory handle");
        entry.file().shmBarrier();
    }

    public int shmUnmap(int handle, int delete) {
        OpenFile entry = lookup(handle);
        if (entry == null) return SQLITE_MISUSE;
        if (memory.sharedMemorySupported()) memory.unmapShared(handle);
        return entry.file().shmUnmap(delete != 0);
    }

    public int fileControl(int handle, int opcode, int argument) {
        OpenFile entry = lookup(handle);
        if (entry == null) return SQLITE_MISUSE;
        if (opcode == SQLITE_FCNTL_LOCKSTATE || opcode == SQLITE_FCNTL_MMAP_SIZE
                || opcode == SQLITE_FCNTL_POWERSAFE_OVERWRITE) {
            boolean wide = opcode == SQLITE_FCNTL_MMAP_SIZE;
            if (!memory.contains(argument, wide ? 8 : 4)) return SQLITE_MISUSE;
            long value = opcode == SQLITE_FCNTL_LOCKSTATE ? 0
                    : wide ? memory.readLong(argument) : memory.readInt(argument);
            NioVfs.LongResult result = entry.file().fileControl(opcode, value);
            if (result.code() == SQLITE_OK) {
                if (wide) memory.writeLong(argument, result.value());
                else memory.writeInt(argument, (int) result.value());
            }
            return result.code();
        }
        return entry.file().fileControl(opcode, 0).code();
    }

    public int delete(int nameAddress, int syncDirectory) {
        String name;
        try {
            name = filename(nameAddress);
        } catch (IllegalArgumentException failure) {
            return SQLITE_CANTOPEN;
        }
        if (observer != null) observer.onIo("before-delete", 0, name, 0, syncDirectory, SQLITE_OK);
        int result = vfs.delete(name, syncDirectory != 0);
        if (observer != null) observer.onIo("after-delete", 0, name, 0, syncDirectory, result);
        return result;
    }

    public int access(int nameAddress, int flags, int outAddress) {
        if (!memory.contains(outAddress, 4)) return SQLITE_MISUSE;
        String name;
        try {
            name = filename(nameAddress);
        } catch (IllegalArgumentException failure) {
            return SQLITE_CANTOPEN;
        }
        NioVfs.IntResult result = vfs.access(name, flags);
        if (result.code() == SQLITE_OK) memory.writeInt(outAddress, result.value());
        return result.code();
    }

    public int fullPath(int nameAddress, int outputSize, int outAddress) {
        if (!memory.contains(outAddress, outputSize) || outputSize == 0) return SQLITE_CANTOPEN;
        String name;
        try {
            name = filename(nameAddress);
        } catch (IllegalArgumentException failure) {
            return SQLITE_CANTOPEN;
        }
        NioVfs.PathResult result = vfs.fullPathname(name);
        if (result.code() != SQLITE_OK) return result.code();
        byte[] path = result.path().getBytes(StandardCharsets.UTF_8);
        if (path.length >= outputSize) return SQLITE_CANTOPEN;
        memory.write(outAddress, path, 0, path.length);
        memory.writeByte(outAddress + path.length, (byte) 0);
        return SQLITE_OK;
    }

    public int random(int address, int amount) {
        if (!memory.contains(address, amount)) return 0;
        int done = 0;
        while (done < amount) {
            int count = Math.min(bytes.length, amount - done);
            buffer.clear().limit(count);
            int received = vfs.randomness(buffer);
            if (received < 0 || received > count) throw new IllegalStateException("Invalid randomness count");
            memory.write(address + done, bytes, 0, received);
            done += received;
            if (received < count) break;
        }
        return done;
    }

    public int sleep(int microseconds) {
        return (int) Math.min(Integer.MAX_VALUE, vfs.sleep(Math.max(0, microseconds)));
    }

    public int time(int outAddress) {
        if (!memory.contains(outAddress, 8)) return SQLITE_MISUSE;
        memory.writeLong(outAddress, vfs.currentTimeMillisJulian());
        return SQLITE_OK;
    }

    public int lastError(int amount, int address) {
        if (amount <= 0 || !memory.contains(address, amount)) return 0;
        byte[] diagnostic = vfs.lastError().getBytes(StandardCharsets.UTF_8);
        int count = Math.min(amount - 1, diagnostic.length);
        memory.write(address, diagnostic, 0, count);
        memory.writeByte(address + count, (byte) 0);
        // Java has no portable OS errno. The diagnostic text is the useful result.
        return 0;
    }

    public int closeAll() {
        int result = SQLITE_OK;
        for (int index = 1; index < slots.size(); index++) {
            Slot slot = slots.get(index);
            if (slot.file == null) continue;
            int closed = close((slot.generation << 16) | index);
            if (result == SQLITE_OK && closed != SQLITE_OK) result = closed;
        }
        return result;
    }

    private int store(OpenFile file) {
        int index;
        Slot slot;
        if (freeSlot != 0) {
            index = freeSlot;
            slot = slots.get(index);
            freeSlot = slot.nextFree;
        } else {
            index = slots.size();
            slot = new Slot();
            slots.add(slot);
        }
        slot.generation++;
        slot.file = file;
        return (slot.generation << 16) | index;
    }

    private OpenFile lookup(int handle) {
        int index = handle & 0xffff;
        if (handle <= 0 || index == 0 || index >= slots.size()) return null;
        Slot slot = slots.get(index);
        return slot.generation == (handle >>> 16) ? slot.file : null;
    }

    private OpenFile remove(int handle) {
        OpenFile file = lookup(handle);
        if (file == null) return null;
        int index = handle & 0xffff;
        Slot slot = slots.get(index);
        slot.file = null;
        // Retire before generation wrap; never let a stale handle address a new file.
        // At most 65535 concurrent slots, with no boxing/allocation on I/O lookup.
        if (slot.generation < 32767) {
            slot.nextFree = freeSlot;
            freeSlot = index;
        }
        return file;
    }

    private String filename(int address) {
        if (address <= 0 || !memory.contains(address, 1)) throw new IllegalArgumentException("Invalid filename address");
        int maximum = (int) Math.min(MAX_PATH_BYTES, memory.byteSize() - address);
        int length = 0;
        while (length < maximum && memory.readByte(address + length) != 0) length++;
        if (length == maximum) throw new IllegalArgumentException("Unterminated filename");
        byte[] encoded = new byte[length];
        memory.read(address, encoded, 0, length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid UTF-8 filename", failure);
        }
    }

    private void notify(OpenFile file, String operation, long offset, int amount, int result) {
        if (observer != null) observer.onIo(operation, file.flags(), file.path(), offset, amount, result);
    }
}
