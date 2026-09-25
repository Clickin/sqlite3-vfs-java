package io.github.clickin.sqlitevfs.adapter;

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

    default boolean contains(int address, int length) {
        return address >= 0 && length >= 0 && (long) address + length <= byteSize();
    }
}
