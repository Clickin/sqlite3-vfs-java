package io.github.clickin.sqlitevfs.engine;

import io.github.clickin.sqlitevfs.NioVfs;
import io.github.clickin.sqlitevfs.adapter.EngineMemory;
import io.github.clickin.sqlitevfs.adapter.VfsBridge;
import java.nio.ByteBuffer;
import run.endive.runtime.HostFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.WasmFunctionHandle;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

import static io.github.clickin.sqlitevfs.SqliteCodes.SQLITE_OK;
import static run.endive.wasm.types.ValType.I32;
import static run.endive.wasm.types.ValType.I64;

/** Endive-specific binding; the filesystem and checked handle bridge remain ordinary Java. */
public final class JvmVfsImports implements AutoCloseable {
    private final GuestMemory memory = new GuestMemory();
    private final VfsBridge bridge;
    private final HostFunction[] functions;

    public JvmVfsImports() {
        this(new NioVfs(), null);
    }

    public JvmVfsImports(NioVfs vfs, VfsBridge.Observer observer) {
        bridge = new VfsBridge(vfs, memory, observer);
        functions = new HostFunction[] {
            function("open", (i, a) -> result(bridge.open((int) a[0], (int) a[1], (int) a[2], (int) a[3])), I32, I32, I32, I32),
            function("close", (i, a) -> result(bridge.close((int) a[0])), I32),
            function("read", (i, a) -> result(bridge.read((int) a[0], (int) a[1], (int) a[2], a[3])), I32, I32, I32, I64),
            function("write", (i, a) -> result(bridge.write((int) a[0], (int) a[1], (int) a[2], a[3])), I32, I32, I32, I64),
            function("truncate", (i, a) -> result(bridge.truncate((int) a[0], a[1])), I32, I64),
            function("sync", (i, a) -> result(bridge.sync((int) a[0], (int) a[1])), I32, I32),
            function("size", (i, a) -> result(bridge.size((int) a[0], (int) a[1])), I32, I32),
            function("lock", (i, a) -> result(bridge.lock((int) a[0], (int) a[1])), I32, I32),
            function("unlock", (i, a) -> result(bridge.unlock((int) a[0], (int) a[1])), I32, I32),
            function("reserved", (i, a) -> result(bridge.reserved((int) a[0], (int) a[1])), I32, I32),
            function("shm_supported", (i, a) -> result(bridge.shmSupported())),
            function("shm_map", (i, a) -> result(bridge.shmMap((int) a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4], (int) a[5])), I32, I32, I32, I32, I32, I32),
            function("shm_lock", (i, a) -> result(bridge.shmLock((int) a[0], (int) a[1], (int) a[2], (int) a[3])), I32, I32, I32, I32),
            new HostFunction("jvfs", "shm_barrier", FunctionType.of(new ValType[] {I32}, new ValType[0]),
                    (i, a) -> {
                        memory.bind(i);
                        bridge.shmBarrier((int) a[0]);
                        return null;
                    }),
            function("shm_unmap", (i, a) -> result(bridge.shmUnmap((int) a[0], (int) a[1])), I32, I32),
            function("delete", (i, a) -> result(bridge.delete((int) a[0], (int) a[1])), I32, I32),
            function("access", (i, a) -> result(bridge.access((int) a[0], (int) a[1], (int) a[2])), I32, I32, I32),
            function("fullpath", (i, a) -> result(bridge.fullPath((int) a[0], (int) a[1], (int) a[2])), I32, I32, I32),
            function("file_control", (i, a) -> result(bridge.fileControl((int) a[0], (int) a[1], (int) a[2])), I32, I32, I32),
            function("random", (i, a) -> result(bridge.random((int) a[0], (int) a[1])), I32, I32),
            function("sleep", (i, a) -> result(bridge.sleep((int) a[0])), I32),
            function("time", (i, a) -> result(bridge.time((int) a[0])), I32),
            function("last_error", (i, a) -> result(bridge.lastError((int) a[0], (int) a[1])), I32, I32)
        };
    }

    private HostFunction function(String name, WasmFunctionHandle function, ValType... parameters) {
        return new HostFunction("jvfs", name, FunctionType.of(parameters, new ValType[] {I32}),
                (instance, arguments) -> {
                    memory.bind(instance);
                    return function.apply(instance, arguments);
                });
    }

    private static long[] result(int code) {
        return new long[] {code};
    }

    public HostFunction[] toHostFunctions() {
        return functions.clone();
    }

    @Override
    public void close() {
        int code = bridge.closeAll();
        if (code != SQLITE_OK) throw new IllegalStateException("VFS cleanup failed: SQLite result " + code);
    }

    private static final class GuestMemory implements EngineMemory {
        private Memory delegate;

        private void bind(Instance instance) {
            Memory incoming = instance.memory();
            if (delegate == null) delegate = incoming;
            else if (delegate != incoming) throw new IllegalStateException("VFS imports belong to another engine instance");
        }

        @Override public boolean sharedMemorySupported() {
            return delegate instanceof MappedGuestMemory && ((MappedGuestMemory) delegate).supportsSharedMappings();
        }
        @Override public void mapShared(int handle, int page, int address, ByteBuffer region) {
            if (!(delegate instanceof MappedGuestMemory)) {
                throw new UnsupportedOperationException("Engine memory does not support shared mappings");
            }
            ((MappedGuestMemory) delegate).mapShared(handle, page, address, region);
        }
        @Override public void unmapShared(int handle) {
            if (!(delegate instanceof MappedGuestMemory)) {
                throw new UnsupportedOperationException("Engine memory does not support shared mappings");
            }
            ((MappedGuestMemory) delegate).unmapShared(handle);
        }
        @Override public long byteSize() { return (long) delegate.pages() * Memory.PAGE_SIZE; }
        @Override public byte readByte(int address) { return delegate.read(address); }
        @Override public void writeByte(int address, byte value) { delegate.writeByte(address, value); }
        @Override public int readInt(int address) { return delegate.readInt(address); }
        @Override public void writeInt(int address, int value) { delegate.writeI32(address, value); }
        @Override public long readLong(int address) { return delegate.readLong(address); }
        @Override public void writeLong(int address, long value) { delegate.writeLong(address, value); }
        @Override public void write(int address, byte[] source, int offset, int length) { delegate.write(address, source, offset, length); }

        @Override
        public void read(int address, byte[] target, int offset, int length) {
            if (delegate instanceof MappedGuestMemory) {
                ((MappedGuestMemory) delegate).read(address, target, offset, length);
                return;
            }
            // Memory.readBytes allocates; reuse the bridge's transfer array instead.
            int index = 0;
            while (index + Long.BYTES <= length) {
                long word = delegate.readLong(address + index);
                for (int b = 0; b < Long.BYTES; b++) target[offset + index + b] = (byte) (word >>> (b * 8));
                index += Long.BYTES;
            }
            while (index < length) {
                target[offset + index] = delegate.read(address + index);
                index++;
            }
        }
    }
}
