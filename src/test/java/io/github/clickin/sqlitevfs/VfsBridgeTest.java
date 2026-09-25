package io.github.clickin.sqlitevfs;

import io.github.clickin.sqlitevfs.adapter.EngineMemory;
import io.github.clickin.sqlitevfs.adapter.VfsBridge;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.clickin.sqlitevfs.SqliteCodes.*;
import static org.junit.jupiter.api.Assertions.*;

class VfsBridgeTest {
    @TempDir Path directory;

    @Test
    void guestReadZeroFillsAcrossTransferChunksAndPreserves64BitOffset() throws Exception {
        TestMemory memory = new TestMemory(200000);
        Path path = directory.resolve("wide.db");
        memory.string(64, path.toString());
        VfsBridge bridge = new VfsBridge(new NioVfs(), memory);
        try {
            assertEquals(SQLITE_OK, bridge.open(64, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_MAIN_DB, 8, 12));
            int handle = memory.readInt(8);
            long offset = (1L << 32) + 17;
            memory.write(1024, new byte[] {1, 2, 3, 4}, 0, 4);
            assertEquals(SQLITE_OK, bridge.write(handle, 1024, 4, offset));
            assertEquals(SQLITE_OK, bridge.size(handle, 16));
            assertEquals(offset + 4, memory.readLong(16));
            Arrays.fill(memory.data, 1023, 1024 + 140001, (byte) 99);
            assertEquals(SQLITE_IOERR_SHORT_READ, bridge.read(handle, 1024, 140000, offset - 2));
            assertArrayEquals(new byte[] {0, 0, 1, 2, 3, 4}, Arrays.copyOfRange(memory.data, 1024, 1030));
            assertArrayEquals(new byte[139994], Arrays.copyOfRange(memory.data, 1030, 141024));
            assertEquals(99, memory.readByte(1023));
            assertEquals(99, memory.readByte(141024));
            assertEquals(SQLITE_OK, bridge.truncate(handle, 0));
        } finally {
            assertEquals(SQLITE_OK, bridge.closeAll());
        }
    }

    @Test
    void invalidOutputPointerDoesNotOpenOrCreateAFile() {
        TestMemory memory = new TestMemory(1024);
        Path path = directory.resolve("must-not-exist");
        memory.string(64, path.toString());
        VfsBridge bridge = new VfsBridge(new NioVfs(), memory);
        assertEquals(SQLITE_MISUSE, bridge.open(64, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_MAIN_DB, 1022, 12));
        assertFalse(Files.exists(path));
        assertEquals(SQLITE_CANTOPEN, bridge.fullPath(64, 2, 16));
        assertEquals(SQLITE_OK, bridge.closeAll());
    }

    @Test
    void closedHandleCannotReadWriteOrCloseItsReplacement() {
        TestMemory memory = new TestMemory(4096);
        memory.string(64, directory.resolve("handles.db").toString());
        VfsBridge bridge = new VfsBridge(new NioVfs(), memory);
        try {
            int flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_MAIN_DB;
            assertEquals(SQLITE_OK, bridge.open(64, flags, 8, 12));
            int closed = memory.readInt(8);
            assertEquals(SQLITE_OK, bridge.close(closed));
            assertEquals(SQLITE_OK, bridge.open(64, flags, 8, 12));
            int current = memory.readInt(8);
            memory.writeByte(1024, (byte) 42);
            assertEquals(SQLITE_MISUSE, bridge.write(closed, 1024, 1, 0));
            assertEquals(SQLITE_MISUSE, bridge.close(closed));
            assertEquals(SQLITE_OK, bridge.write(current, 1024, 1, 0));
            assertEquals(SQLITE_OK, bridge.read(current, 1025, 1, 0));
            assertEquals(42, memory.readByte(1025));
            assertEquals(SQLITE_MISUSE, bridge.read(current, -1, 1, 0));
            assertEquals(SQLITE_MISUSE, bridge.write(current, 4095, 2, 0));
            assertEquals(SQLITE_MISUSE, bridge.write(current, 1024, 1, Long.MAX_VALUE));
        } finally {
            assertEquals(SQLITE_OK, bridge.closeAll());
        }
    }

    @Test
    void fileControlPreservesTypedOutParametersAndDoesNotAdvertiseMmap() {
        TestMemory memory = new TestMemory(4096);
        memory.string(64, directory.resolve("controls.db").toString());
        VfsBridge bridge = new VfsBridge(new NioVfs(), memory);
        try {
            assertEquals(SQLITE_OK, bridge.open(64, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_MAIN_DB, 8, 12));
            int handle = memory.readInt(8);
            assertEquals(SQLITE_OK, bridge.lock(handle, SQLITE_LOCK_SHARED));
            assertEquals(SQLITE_OK, bridge.lock(handle, SQLITE_LOCK_RESERVED));
            assertEquals(SQLITE_OK, bridge.reserved(handle, 16));
            assertEquals(1, memory.readInt(16));
            assertEquals(SQLITE_OK, bridge.fileControl(handle, SQLITE_FCNTL_LOCKSTATE, 20));
            assertEquals(SQLITE_LOCK_RESERVED, memory.readInt(20));
            memory.writeLong(24, 1L << 40);
            assertEquals(SQLITE_OK, bridge.fileControl(handle, SQLITE_FCNTL_MMAP_SIZE, 24));
            assertEquals(0L, memory.readLong(24));
            memory.writeInt(32, -1);
            assertEquals(SQLITE_OK, bridge.fileControl(handle, SQLITE_FCNTL_POWERSAFE_OVERWRITE, 32));
            assertEquals(0, memory.readInt(32));
            assertEquals(SQLITE_NOTFOUND, bridge.fileControl(handle, Integer.MAX_VALUE, 0));
        } finally {
            assertEquals(SQLITE_OK, bridge.closeAll());
        }
    }

    @Test
    void malformedAndUnterminatedFilenamesAreRejectedAtGuestBoundary() {
        TestMemory memory = new TestMemory(128);
        memory.write(64, new byte[] {(byte) 0xc3, 0x28, 0}, 0, 3);
        VfsBridge bridge = new VfsBridge(new NioVfs(), memory);
        int flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_MAIN_DB;
        assertEquals(SQLITE_CANTOPEN, bridge.open(64, flags, 8, 12));
        Arrays.fill(memory.data, 64, 128, (byte) 'a');
        assertEquals(SQLITE_CANTOPEN, bridge.open(64, flags, 8, 12));
        assertEquals(SQLITE_OK, bridge.closeAll());
    }

    private static final class TestMemory implements EngineMemory {
        private final byte[] data;
        private final ByteBuffer buffer;
        TestMemory(int size) { data = new byte[size]; buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }
        void string(int address, String text) { byte[] bytes = text.getBytes(StandardCharsets.UTF_8); write(address, bytes, 0, bytes.length); writeByte(address + bytes.length, (byte) 0); }
        @Override public long byteSize() { return data.length; }
        @Override public byte readByte(int address) { return data[address]; }
        @Override public void writeByte(int address, byte value) { data[address] = value; }
        @Override public int readInt(int address) { return buffer.getInt(address); }
        @Override public void writeInt(int address, int value) { buffer.putInt(address, value); }
        @Override public long readLong(int address) { return buffer.getLong(address); }
        @Override public void writeLong(int address, long value) { buffer.putLong(address, value); }
        @Override public void read(int address, byte[] target, int offset, int length) { System.arraycopy(data, address, target, offset, length); }
        @Override public void write(int address, byte[] source, int offset, int length) { System.arraycopy(source, offset, data, address, length); }
    }
}
