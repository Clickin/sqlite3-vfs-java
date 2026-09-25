package io.github.clickin.sqlitevfs;

import io.github.clickin.sqlitevfs.RollbackFileTest.Child;
import io.github.clickin.sqlitevfs.adapter.EngineMemory;
import io.github.clickin.sqlitevfs.adapter.VfsBridge;
import io.github.clickin.sqlitevfs.engine.MappedGuestMemory;
import io.roastedroot.sqlite4j.JDBC;
import io.roastedroot.sqlite4j.SQLiteConfig;
import io.roastedroot.sqlite4j.core.WasmDB;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.DataSegment;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.PassiveDataSegment;

import static io.github.clickin.sqlitevfs.EngineNativeTest.scalar;
import static io.github.clickin.sqlitevfs.SqliteCodes.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
class WalIntegrationTest {
    @TempDir Path directory;

    private static Connection openWal(Path path) throws SQLException {
        Connection connection = new JDBC().connect("jdbc:sqlite:" + path, new Properties());
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=0");
                statement.execute("PRAGMA wal_autocheckpoint=0");
                statement.execute("PRAGMA synchronous=FULL");
            }
            assertEquals("normal", scalar(connection, "PRAGMA locking_mode"));
            assertEquals("wal", scalar(connection, "PRAGMA journal_mode=WAL"));
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    private static void busy(Statement statement, String sql) {
        SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql));
        assertEquals(SQLITE_BUSY, failure.getErrorCode() & 0xff);
    }

    private static void checkpoint(Connection connection, int expectedBusy) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            assertTrue(result.next());
            assertEquals(expectedBusy, result.getInt(1));
            if (expectedBusy == 0) {
                assertEquals(0, result.getInt(2));
                assertEquals(0, result.getInt(3));
            } else {
                assertTrue(result.getInt(2) > result.getInt(3), "snapshot must pin uncheckpointed frames");
            }
        }
    }

    @Test
    void nativeAndJvmShareWalWriterLocksSnapshotsCheckpointAndReopen() throws Exception {
        Path path = directory.resolve("shared.db");
        try (Child nativeDb = Child.nativeDb(path, true)) {
            assertEquals("wal", nativeDb.ok("PRAGMA journal_mode=WAL"));
            nativeDb.ok("PRAGMA wal_autocheckpoint=0");
            try (Connection engine = openWal(path); Statement writer = engine.createStatement()) {
                assertEquals("30", scalar(engine, "SELECT sum(value) FROM sample"));
                nativeDb.ok("BEGIN IMMEDIATE");
                busy(writer, "BEGIN IMMEDIATE");
                nativeDb.ok("ROLLBACK");
                writer.execute("BEGIN IMMEDIATE");
                nativeDb.busy("BEGIN IMMEDIATE");
                writer.execute("UPDATE sample SET value=value+1 WHERE id=1");
                writer.execute("COMMIT");
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));

                writer.execute("BEGIN");
                assertEquals("31", scalar(engine, "SELECT sum(value) FROM sample"));
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                assertEquals("31", scalar(engine, "SELECT sum(value) FROM sample"));
                writer.execute("ROLLBACK");
                assertEquals("32", scalar(engine, "SELECT sum(value) FROM sample"));

                nativeDb.ok("BEGIN");
                assertEquals("32", nativeDb.ok("SELECT sum(value) FROM sample"));
                writer.execute("UPDATE sample SET value=value+5 WHERE id=1");
                assertEquals("32", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals("37", scalar(engine, "SELECT sum(value) FROM sample"));
                assertTrue(Files.exists(Path.of(path + "-wal")));
                assertTrue(Files.exists(Path.of(path + "-shm")));
                checkpoint(engine, 1);
                nativeDb.ok("ROLLBACK");
                checkpoint(engine, 0);
                assertEquals("37", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals("ok", scalar(engine, "PRAGMA integrity_check"));
            }
            assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
            nativeDb.ok("UPDATE sample SET value=value+3 WHERE id=1");
            try (Connection reopened = openWal(path)) {
                assertEquals("40", scalar(reopened, "SELECT sum(value) FROM sample"));
                assertEquals("ok", scalar(reopened, "PRAGMA integrity_check"));
            }
        }
        try (Connection reopened = openWal(path)) {
            assertEquals("40", scalar(reopened, "SELECT sum(value) FROM sample"));
            checkpoint(reopened, 0);
            assertEquals("ok", scalar(reopened, "PRAGMA integrity_check"));
        }
    }

    @ParameterizedTest(name = "first attach while native holds {0}")
    @ValueSource(strings = {"BEGIN", "BEGIN IMMEDIATE"})
    void firstAttachIsBusyDuringNativeTransactionAndRetriesWithoutDisturbingIt(String begin) throws Exception {
        Path path = directory.resolve("first-attach-" + begin.replace(' ', '-') + ".db");
        try (Child nativeDb = Child.nativeDb(path, true)) {
            assertEquals("wal", nativeDb.ok("PRAGMA journal_mode=WAL"));
            nativeDb.ok("PRAGMA wal_autocheckpoint=0");
            nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
            nativeDb.ok(begin);
            assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
            SQLiteConfig config = new SQLiteConfig();
            config.setBusyTimeout(0);
            // Use the engine directly so JDBC's connection-configuration pragmas
            // cannot attach before the operation whose BUSY/retry we are checking.
            WasmDB database = new WasmDB("jdbc:sqlite:" + path, path.toString(), config);
            try {
                database.open(path.toString(), config.getOpenModeFlags());
                assertEquals(SQLITE_BUSY, database._exec("SELECT sum(value) FROM sample") & 0xff);
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                nativeDb.ok("ROLLBACK");
                assertEquals(SQLITE_OK, database._exec("UPDATE sample SET value=value+5 WHERE id=1"));
                assertEquals("36", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals(SQLITE_OK, database._exec("PRAGMA integrity_check"));
            } finally {
                database.close();
            }
            assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
        }
        try (Connection reopened = openWal(path)) {
            assertEquals("36", scalar(reopened, "SELECT sum(value) FROM sample"));
            assertEquals("ok", scalar(reopened, "PRAGMA integrity_check"));
        }
    }

    @ParameterizedTest(name = "WAL writer opened first: {0}")
    @ValueSource(booleans = {true, false})
    void closingEitherJvmConnectionRetainsTheOtherMappingsAndWriterLocks(boolean writerFirst) throws Exception {
        Path path = directory.resolve("close-" + writerFirst + ".db");
        try (Child nativeDb = Child.nativeDb(path, true)) {
            assertEquals("wal", nativeDb.ok("PRAGMA journal_mode=WAL"));
            try (Connection first = openWal(path); Connection second = openWal(path)) {
                Connection writer = writerFirst ? first : second;
                Connection idle = writerFirst ? second : first;
                assertEquals("30", scalar(idle, "SELECT sum(value) FROM sample"));
                try (Statement statement = writer.createStatement()) {
                    statement.execute("BEGIN IMMEDIATE");
                    idle.close();
                    nativeDb.busy("BEGIN IMMEDIATE");
                    statement.execute("UPDATE sample SET value=value+4 WHERE id=1");
                    statement.execute("COMMIT");
                    assertEquals("34", nativeDb.ok("SELECT sum(value) FROM sample"));
                }
                writer.close();
            }
            nativeDb.ok("UPDATE sample SET value=value+2 WHERE id=1");
            try (Connection reopened = openWal(path)) {
                assertEquals("36", scalar(reopened, "SELECT sum(value) FROM sample"));
                checkpoint(reopened, 0);
                assertEquals("ok", scalar(reopened, "PRAGMA integrity_check"));
            }
            assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
        }
    }

    private NioVfs.File openFile(Path path) {
        NioVfs.OpenResult opened = new NioVfs().open(path.toString(),
                SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE);
        assertEquals(SQLITE_OK, opened.code());
        assertEquals(SQLITE_OK, opened.file().lock(SQLITE_LOCK_SHARED));
        return opened.file();
    }

    @Test
    void nativeCommitChangesAnAlreadyReturnedGuestAliasWithoutAnotherMapCall() throws Exception {
        Path path = directory.resolve("live-alias.db");
        try (Child nativeDb = Child.nativeDb(path, true)) {
            assertEquals("wal", nativeDb.ok("PRAGMA journal_mode=WAL"));
            nativeDb.ok("PRAGMA wal_autocheckpoint=0");
            nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
            NioVfs.File file = openFile(path);
            MappedGuestMemory memory = new MappedGuestMemory(new MemoryLimits(2, 4));
            try {
                NioVfs.ShmResult mapped = file.shmMap(0, 32768, false);
                assertEquals(SQLITE_OK, mapped.code());
                assertNotNull(mapped.region());
                memory.mapShared(1, 0, 65528, mapped.region());
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals(3007000, memory.readInt(65528));
                int change = memory.readInt(65528 + 8);
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                // No hostcall, re-map, or copied snapshot refresh occurs here.
                assertEquals(change + 1, memory.readInt(65528 + 8));
                assertEquals(change + 1, memory.atomicReadInt(65528 + 8));
                memory.copy(128, 65528, 48);
                assertEquals(change + 1, memory.readInt(136));
                assertEquals(2, memory.grow(1));
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                assertEquals(change + 2, memory.readInt(65528 + 8));
                assertEquals("33", nativeDb.ok("SELECT sum(value) FROM sample"));
            } finally {
                memory.unmapShared(1);
                assertEquals(SQLITE_OK, file.close());
            }
            assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
        }
    }

    @Test
    void guestScalarsBulkCopiesAtomicsAndPassiveSegmentsCrossLiveAliasBoundaries() throws Exception {
        Path path = directory.resolve("memory.db");
        NioVfs.File file = openFile(path);
        MappedGuestMemory memory = new MappedGuestMemory(new MemoryLimits(4, 6));
        int start = 65528;
        try {
            assertNull(file.shmMap(0, 32768, false).region());
            NioVfs.ShmResult first = file.shmMap(0, 32768, true);
            assertEquals(SQLITE_OK, first.code());
            assertNull(file.shmMap(1, 32768, false).region());
            NioVfs.ShmResult second = file.shmMap(1, 32768, true);
            assertEquals(SQLITE_OK, second.code());
            ByteBuffer view = first.region().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer other = second.region().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            memory.writeI32(start, 71);
            memory.mapShared(1, 0, start, first.region());
            memory.mapShared(2, 1, 131072, second.region());
            memory.writeI32(start, 0x78563412);
            assertEquals(0x78563412, view.getInt(0));
            view.putInt(0, 0x44332211);
            assertEquals(0x44332211, memory.readInt(start));
            assertEquals(0x11, memory.readU8(start));
            memory.writeLong(start + 8, 0x8877665544332211L);
            assertEquals(0x8877665544332211L, view.getLong(8));
            memory.writeShort(start + 16, (short) 0xfffe);
            assertEquals(65534, memory.readU16(start + 16));
            memory.writeF32(start + 20, -1.25f);
            assertEquals(-1.25f, view.getFloat(20));
            assertEquals(Float.floatToRawIntBits(-1.25f), memory.readF32(start + 20));
            memory.writeF64(start + 24, Math.PI);
            assertEquals(Math.PI, memory.readDouble(start + 24));
            assertEquals(Double.doubleToRawLongBits(Math.PI), memory.readF64(start + 24));

            byte[] source = new byte[32784];
            for (int i = 0; i < source.length; i++) source[i] = (byte) (i * 31 + 7);
            memory.write(start - 8, source, 0, source.length);
            assertArrayEquals(source, memory.readBytes(start - 8, source.length));
            assertEquals(source[8], view.get(0));
            assertEquals(source[32775], view.get(32767));
            byte[] expected = source.clone();
            System.arraycopy(expected, 0, expected, 3, expected.length - 3);
            memory.copy(start - 5, start - 8, source.length - 3);
            assertArrayEquals(expected, memory.readBytes(start - 8, source.length));
            System.arraycopy(expected, 3, expected, 0, expected.length - 3);
            memory.copy(start - 8, start - 5, source.length - 3);
            assertArrayEquals(expected, memory.readBytes(start - 8, source.length));
            memory.writeLong(start - 3, 0x8070605040302010L);
            assertEquals(0x8070605040302010L, memory.readLong(start - 3));
            memory.fill((byte) 0x5a, start - 2, start + 32770);
            assertEquals(0x5a5a5a5a, memory.readInt(start - 2));
            assertEquals(0x5a5a5a5a, memory.readInt(start + 32766));

            memory.atomicWriteInt(start, 0x11223344);
            assertEquals((byte) 0x44, memory.atomicAddByte(start, (byte) 1));
            assertEquals(0x11223345, view.getInt(0));
            assertEquals((short) 0x1122, memory.atomicXchgShort(start + 2, (short) 0xaabb));
            assertEquals(0xaabb3345, view.getInt(0));
            assertEquals(0xaabb3345, memory.atomicCmpxchgInt(start, 1, 2));
            assertEquals(0xaabb3345, view.getInt(0));
            assertEquals(0xaabb3345, memory.atomicCmpxchgInt(start, 0xaabb3345, 7));
            assertEquals(7, view.getInt(0));
            memory.atomicWriteLong(start + 8, 10);
            assertEquals(10, memory.atomicAddLong(start + 8, 5));
            assertEquals(15, view.getLong(8));
            assertThrows(WasmEngineException.class, () -> memory.atomicReadInt(start + 1));

            memory.initialize(null, new DataSegment[] {new PassiveDataSegment(new byte[] {9, 8, 7, 6})});
            memory.initPassiveSegment(0, start - 1, 0, 4);
            assertArrayEquals(new byte[] {9, 8, 7, 6}, memory.readBytes(start - 1, 4));
            assertEquals(8, view.get(0));
            memory.drop(0);
            assertThrows(IndexOutOfBoundsException.class, () -> memory.initPassiveSegment(0, start, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> memory.mapShared(3, 0, start + 8, second.region()));
            assertEquals(4, memory.grow(1));
            memory.writeI32(131072, 42);
            assertEquals(42, other.getInt(0));
            memory.unmapShared(1);
            assertEquals(71, memory.readInt(start), "detach restores heap storage, never copies mapped bytes back");
            assertEquals(42, memory.readInt(131072));
            memory.mapShared(3, 0, start, first.region());
            memory.writeI32(start, 93);
            assertEquals(93, view.getInt(0));
            memory.mapShared(4, 0, 196608, first.region().asReadOnlyBuffer());
            assertEquals(93, memory.readInt(196608));
            assertThrows(java.nio.ReadOnlyBufferException.class, () -> memory.writeI32(196608, 1));
            memory.unmapShared(4);
            memory.zero();
            assertEquals(0, view.getInt(0));
            assertEquals(0, other.getInt(0));
            assertEquals(0, memory.readLong(4 * 65536));
        } finally {
            memory.unmapShared(1);
            memory.unmapShared(2);
            memory.unmapShared(3);
            memory.unmapShared(4);
            assertEquals(SQLITE_OK, file.shmUnmap(true));
            assertEquals(SQLITE_OK, file.close());
        }
        assertFalse(Files.exists(Path.of(path + "-shm")));
    }

    @Test
    void bridgeChecksGuestOffsetsAndDetachesAliasesBeforeClosingTheFile() throws Exception {
        MappedGuestMemory guest = new MappedGuestMemory(new MemoryLimits(3, 4));
        EngineMemory memory = new EngineMemory() {
            public long byteSize() { return (long) guest.pages() * 65536; }
            public byte readByte(int address) { return guest.read(address); }
            public void writeByte(int address, byte value) { guest.writeByte(address, value); }
            public int readInt(int address) { return guest.readInt(address); }
            public void writeInt(int address, int value) { guest.writeI32(address, value); }
            public long readLong(int address) { return guest.readLong(address); }
            public void writeLong(int address, long value) { guest.writeLong(address, value); }
            public void read(int address, byte[] target, int offset, int length) { guest.read(address, target, offset, length); }
            public void write(int address, byte[] source, int offset, int length) { guest.write(address, source, offset, length); }
            public boolean sharedMemorySupported() { return guest.supportsSharedMappings(); }
            public void mapShared(int handle, int page, int address, ByteBuffer region) { guest.mapShared(handle, page, address, region); }
            public void unmapShared(int handle) { guest.unmapShared(handle); }
        };
        VfsBridge bridge = new VfsBridge(new NioVfs(), memory);
        Path path = directory.resolve("bridge.db");
        guest.writeCString(128, path.toString());
        assertEquals(SQLITE_OK, bridge.open(128, SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, 8, 12));
        int handle = guest.readInt(8);
        try {
            assertEquals(1, bridge.shmSupported());
            assertEquals(SQLITE_OK, bridge.lock(handle, SQLITE_LOCK_SHARED));
            assertEquals(SQLITE_MISUSE, bridge.shmMap(handle, 0, 32768, 1, -8, 16));
            assertEquals(0, guest.readInt(16));
            assertEquals(SQLITE_MISUSE, bridge.shmMap(handle, 0, 32768, 1, 65536, 65540));
            assertEquals(SQLITE_OK, bridge.shmMap(handle, 0, 32768, 0, 65536, 16));
            assertEquals(0, guest.readInt(16));
            guest.writeI32(65536, 123);
            // SQLite passes writeLock=2 as bExtend; C truth is any nonzero integer.
            assertEquals(SQLITE_OK, bridge.shmMap(handle, 0, 32768, 2, 65536, 16));
            assertEquals(1, guest.readInt(16));
            guest.writeI32(65536, 456);
            assertEquals(SQLITE_OK, bridge.close(handle));
            assertEquals(123, guest.readInt(65536));
            assertEquals(SQLITE_MISUSE, bridge.shmMap(handle, 0, 32768, 1, 65536, 16));
            // Closing an old generation must not detach a newly opened file's alias.
            assertEquals(SQLITE_OK, bridge.open(128, SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_READWRITE, 8, 12));
            int fresh = guest.readInt(8);
            assertNotEquals(handle, fresh);
            assertEquals(SQLITE_OK, bridge.lock(fresh, SQLITE_LOCK_SHARED));
            assertEquals(SQLITE_OK, bridge.shmMap(fresh, 0, 32768, 1, 65536, 16));
            guest.writeI32(65536, 789);
            assertEquals(SQLITE_MISUSE, bridge.close(handle));
            assertEquals(789, guest.readInt(65536));
            assertEquals(SQLITE_OK, bridge.shmUnmap(fresh, 1));
            assertEquals(123, guest.readInt(65536));
        } finally {
            assertEquals(SQLITE_OK, bridge.closeAll());
        }
    }
}
