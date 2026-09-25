package io.github.clickin.sqlitevfs.adapter;

import java.nio.ByteBuffer;

/** Little-endian guest memory. Addresses are offsets, never Java/native pointers. */
public interface EngineMemory {
    long byteSize();
    byte readByte(int address);
    void writeByte(int address, byte value);
    int readInt(int address);
    void writeInt(int address, int value);
    long readLong(int address);
    void writeLong(int address, long value);
    void read(int address, byte[] target, int offset, int length);
    void write(int address, byte[] source, int offset, int length);

    /** True only when guest loads/stores can directly alias an OS shared mapping. */
    default boolean sharedMemorySupported() { return false; }

    /** Attach without copying. The mapping remains owned by the VFS. */
    default void mapShared(int handle, int page, int address, ByteBuffer region) {
        throw new UnsupportedOperationException("Guest memory cannot alias shared mappings");
    }

    /** Detach all aliases for a file before the VFS unmaps their backing storage. */
    default void unmapShared(int handle) {
        throw new UnsupportedOperationException("Guest memory cannot alias shared mappings");
    }

    default boolean contains(int address, int length) {
        return address >= 0 && length >= 0 && (long) address + length <= byteSize();
    }
}
