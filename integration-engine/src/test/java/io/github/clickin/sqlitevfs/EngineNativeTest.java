package io.github.clickin.sqlitevfs;

import io.github.clickin.sqlitevfs.RollbackFileTest.Child;
import io.roastedroot.sqlite4j.JDBC;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class EngineNativeTest {
    @TempDir Path directory;

    static Connection open(Path path) throws SQLException {
        Connection connection = new JDBC().connect("jdbc:sqlite:" + path, new Properties());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=0");
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA synchronous=FULL");
        }
        return connection;
    }

    static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    @Test
    void realEngineAndNativeWritersObserveEachOtherAndCommitTheSameFile() throws Exception {
        Path path = directory.resolve("writers.db");
        try (Child nativeDb = Child.nativeDb(path, true); Connection engine = open(path);
             Statement statement = engine.createStatement()) {
            assertEquals("30", scalar(engine, "SELECT sum(value) FROM sample"));
            assertEquals("delete", scalar(engine, "PRAGMA journal_mode"));
            assertEquals("2", scalar(engine, "PRAGMA synchronous"));
            nativeDb.ok("BEGIN IMMEDIATE");
            SQLException busy = assertThrows(SQLException.class, () -> statement.execute("BEGIN IMMEDIATE"));
            assertEquals(5, busy.getErrorCode() & 0xff);
            nativeDb.ok("ROLLBACK");
            statement.execute("BEGIN IMMEDIATE");
            nativeDb.busy("BEGIN IMMEDIATE");
            statement.execute("UPDATE sample SET value=value+1 WHERE id=1");
            statement.execute("COMMIT");
            assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
            nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
            assertEquals("32", scalar(engine, "SELECT sum(value) FROM sample"));
            assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
            assertEquals("ok", scalar(engine, "PRAGMA integrity_check"));
        }
    }

    @Test
    void nativeReaderBlocksEngineCommitAndEnginePendingBlocksNewReaders() throws Exception {
        Path path = directory.resolve("pending.db");
        try (Child oldReader = Child.nativeDb(path, true);
             Child newReader = Child.nativeDb(path, false);
             Connection engine = open(path); Statement statement = engine.createStatement()) {
            oldReader.ok("BEGIN");
            assertEquals("30", oldReader.ok("SELECT sum(value) FROM sample"));
            statement.execute("BEGIN IMMEDIATE");
            statement.execute("UPDATE sample SET value=value+1 WHERE id=1");
            SQLException busy = assertThrows(SQLException.class, () -> statement.execute("COMMIT"));
            assertEquals(5, busy.getErrorCode() & 0xff);
            newReader.busy("SELECT sum(value) FROM sample");
            oldReader.ok("ROLLBACK");
            statement.execute("COMMIT");
            assertEquals("31", newReader.ok("SELECT sum(value) FROM sample"));
            assertEquals("ok", newReader.ok("PRAGMA integrity_check"));
        }
    }

    @Test
    void closingAnotherEngineInstanceDoesNotEraseTheWriterLock() throws Exception {
        Path path = directory.resolve("instances.db");
        try (Child nativeDb = Child.nativeDb(path, true);
             Connection first = open(path); Connection second = open(path);
             Statement writer = first.createStatement()) {
            assertEquals("30", scalar(second, "SELECT sum(value) FROM sample"));
            writer.execute("BEGIN IMMEDIATE");
            second.close();
            nativeDb.busy("BEGIN IMMEDIATE");
            writer.execute("UPDATE sample SET value=value+5 WHERE id=1");
            writer.execute("COMMIT");
            assertEquals("35", nativeDb.ok("SELECT sum(value) FROM sample"));
            assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
        }
    }

    @Test
    void ordinaryWalUsesSharedMemoryWithoutExclusiveLocking() throws Exception {
        Path path = directory.resolve("wal-shared.db");
        try (Connection engine = open(path); Statement statement = engine.createStatement()) {
            statement.execute("CREATE TABLE data(value)");
            assertEquals("normal", scalar(engine, "PRAGMA locking_mode"));
            assertEquals("wal", scalar(engine, "PRAGMA journal_mode=WAL"));
            statement.execute("INSERT INTO data VALUES(7)");
            assertEquals("7", scalar(engine, "SELECT value FROM data"));
            assertEquals("ok", scalar(engine, "PRAGMA integrity_check"));
            assertTrue(java.nio.file.Files.exists(Path.of(path + "-wal")));
            assertTrue(java.nio.file.Files.exists(Path.of(path + "-shm")));
        }
    }
}
