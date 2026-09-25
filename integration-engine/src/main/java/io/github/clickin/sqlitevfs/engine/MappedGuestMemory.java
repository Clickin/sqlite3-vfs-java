package io.github.clickin.sqlitevfs.engine;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ConstantEvaluators;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.ActiveDataSegment;
import run.endive.wasm.types.DataSegment;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.PassiveDataSegment;

/**
 * Serialized SQLite guest memory with live aliases of the VFS's OS mappings.
 * Ordinary heap accesses stay in Endive; mapped aligned words use native atomic
 * loads/stores, not copies synchronized at hostcall boundaries. The VFS owns the
 * mappings and must detach aliases before releasing them.
 */
public final class MappedGuestMemory implements Memory {
    private static final VarHandle INTS = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONGS = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle SHORTS = MethodHandles.byteBufferViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle ARRAY_INTS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final Alias[] EMPTY = new Alias[0];
    private final ByteArrayMemory heap;
    private Alias[] aliases = EMPTY;
    private int aliasCount;
    private DataSegment[] dataSegments;

    private static final class Alias {
        final int handle;
        final int page;
        final int start;
        final int end;
        final ByteBuffer bytes;

        Alias(int handle, int page, int start, int end, ByteBuffer bytes) {
            this.handle = handle;
            this.page = page;
            this.start = start;
            this.end = end;
            this.bytes = bytes;
        }

        boolean contains(int address, int width) {
            return address >= start && (long) address + width <= end;
        }
    }

    public MappedGuestMemory(MemoryLimits limits) {
        heap = new ByteArrayMemory(limits);
    }

    public boolean supportsSharedMappings() {
        // SQLite serializes an instance; this is not a WebAssembly threads/futex bridge.
        return !shared() && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                && INTS.isAccessModeSupported(VarHandle.AccessMode.COMPARE_AND_SET)
                && LONGS.isAccessModeSupported(VarHandle.AccessMode.COMPARE_AND_SET);
    }

    public void mapShared(int handle, int page, int address, ByteBuffer region) {
        Objects.requireNonNull(region);
        int length = region.capacity();
        if (!supportsSharedMappings()) throw new UnsupportedOperationException("Shared guest mappings unavailable");
        check(address, length);
        if (handle <= 0 || page < 0 || address == 0 || (address & 7) != 0 || length == 0
                || (length & 7) != 0 || !region.isDirect() || region.alignmentOffset(0, 8) != 0) {
            throw new IllegalArgumentException("Invalid shared mapping alignment or identity");
        }
        for (int i = 0; i < aliasCount; i++) {
            Alias alias = aliases[i];
            if (alias.handle == handle && alias.page == page) {
                if (alias.start != address || alias.end != address + length) {
                    throw new IllegalArgumentException("Shared page changed its guest address");
                }
                return;
            }
            if (address < alias.end && address + length > alias.start) {
                throw new IllegalArgumentException("Overlapping shared guest mappings");
            }
        }
        ByteBuffer view = region.duplicate().clear().order(ByteOrder.LITTLE_ENDIAN);
        Alias added = new Alias(handle, page, address, address + length, view);
        Alias[] next = Arrays.copyOf(aliases, aliasCount + 1);
        int position = aliasCount;
        while (position > 0 && aliases[position - 1].start > address) {
            next[position] = aliases[position - 1];
            position--;
        }
        next[position] = added;
        aliases = next;
        aliasCount++;
        VarHandle.fullFence();
    }

    public void unmapShared(int handle) {
        // Compact in place: close must not need an allocation to detach live views.
        int retained = 0;
        for (int i = 0; i < aliasCount; i++) {
            Alias alias = aliases[i];
            if (alias.handle != handle) aliases[retained++] = alias;
        }
        if (retained == aliasCount) return;
        Arrays.fill(aliases, retained, aliasCount, null);
        aliasCount = retained;
        if (retained == 0) aliases = EMPTY;
        VarHandle.fullFence();
    }

    /** First intersecting alias, with an allocation-free ordinary-heap fast path. */
    private Alias intersect(int address, int width) {
        int count = aliasCount;
        if (count == 0 || width == 0 || address >= aliases[count - 1].end
                || (long) address + width <= aliases[0].start) return null;
        int low = 0, high = count;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (aliases[middle].end <= address) low = middle + 1;
            else high = middle;
        }
        return low < count && aliases[low].start < (long) address + width ? aliases[low] : null;
    }

    private void check(int address, int length) {
        if (address < 0 || length < 0 || (long) address + length > (long) pages() * PAGE_SIZE) {
            throw new WasmEngineException("out of bounds memory access");
        }
    }

    @Override public int pages() { return heap.pages(); }
    @Override public int grow(int pages) { return heap.grow(pages); }
    @Override public int initialPages() { return heap.initialPages(); }
    @Override public int maximumPages() { return heap.maximumPages(); }
    @Override public boolean shared() { return heap.shared(); }
    @Override public Object lock(int address) { return heap.lock(address); }
    // Mappings are permitted only for non-shared Wasm memories, whose wait traps
    // and notify returns zero. Shared Wasm memories remain entirely heap-backed.
    @Override public int waitOn(int address, int value, long timeout) { return heap.waitOn(address, value, timeout); }
    @Override public int waitOn(int address, long value, long timeout) { return heap.waitOn(address, value, timeout); }
    @Override public int notify(int address, int count) { return heap.notify(address, count); }
    @Override public void atomicFence() { VarHandle.fullFence(); }

    @Override public void initialize(Instance instance, DataSegment[] segments) { initialize(instance, segments, 0); }

    @Override
    public void initialize(Instance instance, DataSegment[] segments, int memoryIndex) {
        dataSegments = segments;
        if (aliases.length == 0) {
            heap.initialize(instance, segments, memoryIndex);
            return;
        }
        if (segments == null) return;
        for (DataSegment segment : segments) {
            if (segment instanceof ActiveDataSegment) {
                ActiveDataSegment active = (ActiveDataSegment) segment;
                if (active.index() == memoryIndex) {
                    int offset = (int) ConstantEvaluators.computeConstantValue(instance, active.offsetInstructions())[0];
                    write(offset, active.data());
                }
            } else if (!(segment instanceof PassiveDataSegment)) {
                throw new WasmEngineException("Unsupported data segment");
            }
        }
    }

    @Override public void initPassiveSegment(int segment, int address, int offset, int size) {
        write(address, dataSegments[segment].data(), offset, size);
    }
    @Override public void drop(int segment) { dataSegments[segment] = PassiveDataSegment.EMPTY; }

    @Override
    public byte read(int address) {
        Alias alias = intersect(address, 1);
        if (alias == null) return heap.read(address);
        int offset = address - alias.start;
        return (byte) ((int) INTS.getVolatile(alias.bytes, offset & ~3) >>> ((offset & 3) * 8));
    }

    @Override
    public void writeByte(int address, byte value) {
        Alias alias = intersect(address, 1);
        if (alias == null) heap.writeByte(address, value);
        else updateSmall(alias, address, 1, value, 0, SET);
    }

    @Override
    public int readInt(int address) {
        Alias alias = intersect(address, 4);
        if (alias == null) return heap.readInt(address);
        if (alias.contains(address, 4) && (address & 3) == 0) {
            return (int) INTS.getVolatile(alias.bytes, address - alias.start);
        }
        return (int) readUnaligned(address, 4);
    }

    @Override
    public void writeI32(int address, int value) {
        Alias alias = intersect(address, 4);
        if (alias == null) heap.writeI32(address, value);
        else if (alias.contains(address, 4) && (address & 3) == 0) INTS.setVolatile(alias.bytes, address - alias.start, value);
        else writeUnaligned(address, 4, value);
    }

    @Override
    public long readLong(int address) {
        Alias alias = intersect(address, 8);
        if (alias == null) return heap.readLong(address);
        if (alias.contains(address, 8) && (address & 7) == 0) {
            return (long) LONGS.getVolatile(alias.bytes, address - alias.start);
        }
        return readUnaligned(address, 8);
    }

    @Override
    public void writeLong(int address, long value) {
        Alias alias = intersect(address, 8);
        if (alias == null) heap.writeLong(address, value);
        else if (alias.contains(address, 8) && (address & 7) == 0) LONGS.setVolatile(alias.bytes, address - alias.start, value);
        else writeUnaligned(address, 8, value);
    }

    @Override
    public short readShort(int address) {
        Alias alias = intersect(address, 2);
        if (alias == null) return heap.readShort(address);
        if (alias.contains(address, 2) && (address & 1) == 0) {
            return (short) SHORTS.getVolatile(alias.bytes, address - alias.start);
        }
        return (short) readUnaligned(address, 2);
    }

    @Override
    public void writeShort(int address, short value) {
        Alias alias = intersect(address, 2);
        if (alias == null) heap.writeShort(address, value);
        else if (alias.contains(address, 2) && (address & 1) == 0) SHORTS.setVolatile(alias.bytes, address - alias.start, value);
        else writeUnaligned(address, 2, value);
    }

    private long readUnaligned(int address, int width) {
        check(address, width);
        long value = 0;
        for (int i = 0; i < width; i++) value |= (read(address + i) & 0xffL) << (8 * i);
        return value;
    }

    private void writeUnaligned(int address, int width, long value) {
        check(address, width);
        for (int i = 0; i < width; i++) writeByte(address + i, (byte) (value >>> (8 * i)));
    }

    @Override public long readU16(int address) { return readShort(address) & 0xffffL; }
    @Override public void writeF32(int address, float value) { writeI32(address, Float.floatToRawIntBits(value)); }
    @Override public long readF32(int address) { return readInt(address); }
    @Override public float readFloat(int address) { return Float.intBitsToFloat(readInt(address)); }
    @Override public void writeF64(int address, double value) { writeLong(address, Double.doubleToRawLongBits(value)); }
    @Override public double readDouble(int address) { return Double.longBitsToDouble(readLong(address)); }
    @Override public long readF64(int address) { return readLong(address); }

    @Override
    public void write(int address, byte[] source, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, source.length);
        check(address, length);
        if (intersect(address, length) == null) {
            heap.write(address, source, offset, length);
            return;
        }
        int end = address + length;
        while (address < end) {
            if ((address & 3) == 0 && end - address >= 4) {
                writeI32(address, (int) ARRAY_INTS.get(source, offset));
                address += 4;
                offset += 4;
            } else writeByte(address++, source[offset++]);
        }
    }

    public void read(int address, byte[] target, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, target.length);
        check(address, length);
        int end = address + length;
        while (address < end) {
            if ((address & 3) == 0 && end - address >= 4) {
                ARRAY_INTS.set(target, offset, readInt(address));
                address += 4;
                offset += 4;
            } else target[offset++] = read(address++);
        }
    }

    @Override public byte[] readBytes(int address, int length) {
        check(address, length);
        byte[] result = new byte[length];
        read(address, result, 0, length);
        return result;
    }

    @Override public void zero() { fill((byte) 0, 0, pages() * PAGE_SIZE); }

    @Override
    public void fill(byte value, int from, int to) {
        check(from, to - from);
        int word = (value & 0xff) * 0x01010101;
        while (from < to) {
            Alias alias = intersect(from, to - from);
            if (alias == null) {
                heap.fill(value, from, to);
                return;
            }
            if (from < alias.start) {
                heap.fill(value, from, alias.start);
                from = alias.start;
            }
            int end = Math.min(to, alias.end);
            while (from < end) {
                if ((from & 3) == 0 && end - from >= 4) {
                    INTS.setVolatile(alias.bytes, from - alias.start, word);
                    from += 4;
                } else writeByte(from++, value);
            }
        }
    }

    @Override
    public void copy(int destination, int source, int size) {
        check(destination, size);
        check(source, size);
        if (intersect(destination, size) == null && intersect(source, size) == null) {
            heap.copy(destination, source, size);
            return;
        }
        if (destination > source && destination < source + size) {
            int remaining = size;
            while (remaining > 0) {
                if (((destination + remaining) & 3) == 0 && ((source + remaining) & 3) == 0 && remaining >= 4) {
                    remaining -= 4;
                    writeI32(destination + remaining, readInt(source + remaining));
                } else {
                    remaining--;
                    writeByte(destination + remaining, read(source + remaining));
                }
            }
        } else {
            int done = 0;
            while (done < size) {
                if (((destination + done) & 3) == 0 && ((source + done) & 3) == 0 && size - done >= 4) {
                    writeI32(destination + done, readInt(source + done));
                    done += 4;
                } else {
                    writeByte(destination + done, read(source + done));
                    done++;
                }
            }
        }
    }

    private Alias atomicAlias(int address, int width) {
        check(address, width);
        if ((address & (width - 1)) != 0) throw new WasmEngineException("unaligned atomic memory access");
        Alias alias = intersect(address, width);
        if (alias != null && !alias.contains(address, width)) throw new WasmEngineException("atomic access crosses shared mapping");
        return alias;
    }

    @Override public int atomicReadInt(int address) {
        Alias alias = atomicAlias(address, 4);
        return alias == null ? heap.atomicReadInt(address) : (int) INTS.getVolatile(alias.bytes, address - alias.start);
    }
    @Override public long atomicReadLong(int address) {
        Alias alias = atomicAlias(address, 8);
        return alias == null ? heap.atomicReadLong(address) : (long) LONGS.getVolatile(alias.bytes, address - alias.start);
    }
    @Override public short atomicReadShort(int address) {
        Alias alias = atomicAlias(address, 2);
        return alias == null ? heap.atomicReadShort(address) : (short) SHORTS.getVolatile(alias.bytes, address - alias.start);
    }
    @Override public byte atomicReadByte(int address) {
        Alias alias = atomicAlias(address, 1);
        return alias == null ? heap.atomicReadByte(address) : read(address);
    }
    @Override public void atomicWriteInt(int address, int value) {
        Alias alias = atomicAlias(address, 4);
        if (alias == null) heap.atomicWriteInt(address, value);
        else INTS.setVolatile(alias.bytes, address - alias.start, value);
    }
    @Override public void atomicWriteLong(int address, long value) {
        Alias alias = atomicAlias(address, 8);
        if (alias == null) heap.atomicWriteLong(address, value);
        else LONGS.setVolatile(alias.bytes, address - alias.start, value);
    }
    @Override public void atomicWriteShort(int address, short value) {
        Alias alias = atomicAlias(address, 2);
        if (alias == null) heap.atomicWriteShort(address, value);
        else SHORTS.setVolatile(alias.bytes, address - alias.start, value);
    }
    @Override public void atomicWriteByte(int address, byte value) {
        Alias alias = atomicAlias(address, 1);
        if (alias == null) heap.atomicWriteByte(address, value);
        else updateSmall(alias, address, 1, value, 0, SET);
    }

    private static final int ADD = 0, AND = 1, OR = 2, XOR = 3, SET = 4, COMPARE = 5;

    private static long replacement(long old, long value, long expected, int operation) {
        switch (operation) {
            case ADD: return old + value;
            case AND: return old & value;
            case OR: return old | value;
            case XOR: return old ^ value;
            case SET: return value;
            case COMPARE: return old == expected ? value : old;
            default: throw new AssertionError(operation);
        }
    }

    private static int updateSmall(Alias alias, int address, int width, int value, int expected, int operation) {
        int offset = address - alias.start;
        int shift = (offset & 3) * 8;
        int mask = width == 1 ? 0xff : 0xffff;
        int positionedMask = mask << shift;
        for (;;) {
            int word = (int) INTS.getVolatile(alias.bytes, offset & ~3);
            int old = (word >>> shift) & mask;
            int changed = (int) replacement(old, value & mask, expected & mask, operation) & mask;
            int next = (word & ~positionedMask) | (changed << shift);
            if ((boolean) INTS.compareAndSet(alias.bytes, offset & ~3, word, next)) return old;
        }
    }

    private long update(int address, int width, long value, long expected, int operation) {
        Alias alias = atomicAlias(address, width);
        if (alias != null && width <= 2) return updateSmall(alias, address, width, (int) value, (int) expected, operation);
        for (;;) {
            long old;
            if (alias == null) {
                switch (width) {
                    case 1: old = heap.atomicReadByte(address); break;
                    case 2: old = heap.atomicReadShort(address); break;
                    case 4: old = heap.atomicReadInt(address); break;
                    default: old = heap.atomicReadLong(address); break;
                }
            } else old = width == 4 ? (int) INTS.getVolatile(alias.bytes, address - alias.start)
                    : (long) LONGS.getVolatile(alias.bytes, address - alias.start);
            long next = replacement(old, value, expected, operation);
            boolean changed;
            if (alias == null) {
                switch (width) {
                    case 1: changed = heap.atomicCmpxchgByte(address, (byte) old, (byte) next) == (byte) old; break;
                    case 2: changed = heap.atomicCmpxchgShort(address, (short) old, (short) next) == (short) old; break;
                    case 4: changed = heap.atomicCmpxchgInt(address, (int) old, (int) next) == (int) old; break;
                    default: changed = heap.atomicCmpxchgLong(address, old, next) == old; break;
                }
            } else changed = width == 4
                    ? (boolean) INTS.compareAndSet(alias.bytes, address - alias.start, (int) old, (int) next)
                    : (boolean) LONGS.compareAndSet(alias.bytes, address - alias.start, old, next);
            if (changed) return old;
        }
    }

    @Override public int atomicAddInt(int a, int v) { return (int) update(a, 4, v, 0, ADD); }
    @Override public int atomicAndInt(int a, int v) { return (int) update(a, 4, v, 0, AND); }
    @Override public int atomicOrInt(int a, int v) { return (int) update(a, 4, v, 0, OR); }
    @Override public int atomicXorInt(int a, int v) { return (int) update(a, 4, v, 0, XOR); }
    @Override public int atomicXchgInt(int a, int v) { return (int) update(a, 4, v, 0, SET); }
    @Override public int atomicCmpxchgInt(int a, int expected, int v) { return (int) update(a, 4, v, expected, COMPARE); }
    @Override public long atomicAddLong(int a, long v) { return update(a, 8, v, 0, ADD); }
    @Override public long atomicAndLong(int a, long v) { return update(a, 8, v, 0, AND); }
    @Override public long atomicOrLong(int a, long v) { return update(a, 8, v, 0, OR); }
    @Override public long atomicXorLong(int a, long v) { return update(a, 8, v, 0, XOR); }
    @Override public long atomicXchgLong(int a, long v) { return update(a, 8, v, 0, SET); }
    @Override public long atomicCmpxchgLong(int a, long expected, long v) { return update(a, 8, v, expected, COMPARE); }
    @Override public short atomicAddShort(int a, short v) { return (short) update(a, 2, v, 0, ADD); }
    @Override public short atomicAndShort(int a, short v) { return (short) update(a, 2, v, 0, AND); }
    @Override public short atomicOrShort(int a, short v) { return (short) update(a, 2, v, 0, OR); }
    @Override public short atomicXorShort(int a, short v) { return (short) update(a, 2, v, 0, XOR); }
    @Override public short atomicXchgShort(int a, short v) { return (short) update(a, 2, v, 0, SET); }
    @Override public short atomicCmpxchgShort(int a, short expected, short v) { return (short) update(a, 2, v, expected, COMPARE); }
    @Override public byte atomicAddByte(int a, byte v) { return (byte) update(a, 1, v, 0, ADD); }
    @Override public byte atomicAndByte(int a, byte v) { return (byte) update(a, 1, v, 0, AND); }
    @Override public byte atomicOrByte(int a, byte v) { return (byte) update(a, 1, v, 0, OR); }
    @Override public byte atomicXorByte(int a, byte v) { return (byte) update(a, 1, v, 0, XOR); }
    @Override public byte atomicXchgByte(int a, byte v) { return (byte) update(a, 1, v, 0, SET); }
    @Override public byte atomicCmpxchgByte(int a, byte expected, byte v) { return (byte) update(a, 1, v, expected, COMPARE); }
}
