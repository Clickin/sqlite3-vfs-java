package io.github.clickin.sqlitevfs;

import io.github.clickin.sqlitevfs.RollbackFileTest.Child;
import io.roastedroot.sqlite4j.JDBC;
import io.roastedroot.sqlite4j.SQLiteConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static io.github.clickin.sqlitevfs.EngineNativeTest.scalar;
import static io.github.clickin.sqlitevfs.SqliteCodes.SQLITE_READONLY;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
class WalScaleTest {
    @TempDir Path directory;

    private static Connection openWal(Path path) throws SQLException {
        Connection connection = new JDBC().connect("jdbc:sqlite:" + path, new Properties());
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=0");
                statement.execute("PRAGMA page_size=4096");
                statement.execute("PRAGMA wal_autocheckpoint=0");
                statement.execute("PRAGMA synchronous=FULL");
            }
            assertEquals("4096", scalar(connection, "PRAGMA page_size"));
            assertEquals("normal", scalar(connection, "PRAGMA locking_mode"));
            assertEquals("wal", scalar(connection, "PRAGMA journal_mode=WAL"));
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void truncateWal(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1), "checkpoint must no longer be busy");
            assertEquals(0, result.getInt(2));
            assertEquals(0, result.getInt(3));
        }
    }

    @Test
    void nativeLateJoinReadsCommittedSnapshotWhileJvmFirstWriterOwnsWal() throws Exception {
        Path path = directory.resolve("jvm-first.db");
        String totals = "SELECT count(*) || ':' || sum(value) FROM sample";
        try (Connection engine = openWal(path); Statement writer = engine.createStatement()) {
            writer.execute("CREATE TABLE sample(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
            writer.execute("BEGIN IMMEDIATE");
            writer.execute("INSERT INTO sample VALUES (1, 10), (2, 20)");
            writer.execute("COMMIT");
            writer.execute("BEGIN IMMEDIATE");
            writer.execute("UPDATE sample SET value=value+5 WHERE id=1");
            writer.execute("INSERT INTO sample VALUES (3, 40)");
            assertEquals("3:75", scalar(engine, totals));

            // The first native connection arrives only after the JVM has created
            // the WAL/index and holds the writer lock with uncommitted changes.
            try (Child nativeDb = Child.nativeWalDb(path, false)) {
                assertEquals("wal", nativeDb.ok("PRAGMA journal_mode"));
                assertEquals("normal", nativeDb.ok("PRAGMA locking_mode"));
                assertEquals("2:30", nativeDb.ok(totals));
                nativeDb.busy("BEGIN IMMEDIATE");
                nativeDb.ok("BEGIN");
                assertEquals("2:30", nativeDb.ok(totals));
                writer.execute("COMMIT");
                assertEquals("2:30", nativeDb.ok(totals));
                nativeDb.ok("ROLLBACK");
                assertEquals("3:75", nativeDb.ok(totals));
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("ROLLBACK");
                assertEquals("3:75", scalar(engine, totals));
                assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
                truncateWal(engine);
            }
        }
        try (Connection reopened = openWal(path)) {
            assertEquals("3:75", scalar(reopened, totals));
            assertEquals("ok", scalar(reopened, "PRAGMA integrity_check"));
        }
    }

    @Test
    void largeTransactionGrowsWalIndexAndKeepsNativeSnapshotUntilCheckpointCanFinish() throws Exception {
        Path path = directory.resolve("multiple-index-regions.db");
        Path wal = Path.of(path + "-wal");
        Path shm = Path.of(path + "-shm");
        String totals = "SELECT count(*) || ':' || sum(id) || ':' || sum(length(payload)) FROM payloads";
        String committed = "5001:12502500:20484096";
        try (Connection engine = openWal(path); Statement writer = engine.createStatement()) {
            writer.execute("CREATE TABLE payloads(id INTEGER PRIMARY KEY, payload BLOB NOT NULL)");
            writer.execute("INSERT INTO payloads VALUES (0, zeroblob(4096))");
            assertEquals(32768L, Files.size(shm));
            try (Child nativeDb = Child.nativeWalDb(path, false)) {
                assertEquals("normal", nativeDb.ok("PRAGMA locking_mode"));
                nativeDb.ok("PRAGMA wal_autocheckpoint=0");
                nativeDb.ok("BEGIN");
                assertEquals("1:0:4096", nativeDb.ok(totals));

                // One commit for all 5,000 overflow-page blobs, not thousands of
                // synchronous single-row commits. The seed remains the pinned snapshot.
                writer.execute("BEGIN IMMEDIATE");
                try (PreparedStatement insert = engine.prepareStatement(
                        "INSERT INTO payloads VALUES (?, zeroblob(4096))")) {
                    for (int id = 1; id <= 5000; id++) {
                        insert.setInt(1, id);
                        insert.executeUpdate();
                    }
                }
                writer.execute("COMMIT");

                assertEquals(committed, scalar(engine, totals));
                assertEquals("1:0:4096", nativeDb.ok(totals));
                assertTrue(Files.size(shm) >= 65536L, "WAL index must grow beyond its first region");
                assertTrue(Files.size(wal) > 32L + 4079L * (4096 + 24),
                        "WAL must contain more frames than the first index region can hold");
                try (ResultSet checkpoint = writer.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                    assertTrue(checkpoint.next());
                    assertEquals(1, checkpoint.getInt(1), "native snapshot must block truncation");
                    assertTrue(checkpoint.getInt(2) > 4079, "committed log must span index regions");
                    assertTrue(checkpoint.getInt(3) < checkpoint.getInt(2),
                            "native snapshot must pin uncheckpointed frames");
                }
                assertEquals("1:0:4096", nativeDb.ok(totals));
                nativeDb.ok("ROLLBACK");
                assertEquals(committed, nativeDb.ok(totals));
                assertEquals(committed, scalar(engine, totals));
                truncateWal(engine);
                assertEquals(0L, Files.size(wal));
                assertEquals(committed, nativeDb.ok(totals));
                assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
            }
        }
        try (Connection reopened = openWal(path); Child nativeDb = Child.nativeWalDb(path, false)) {
            assertEquals(committed, scalar(reopened, totals));
            assertEquals(committed, nativeDb.ok(totals));
            assertEquals("ok", scalar(reopened, "PRAGMA integrity_check"));
            assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
        }
    }

    @Test
    void readonlyMainDatabaseReadsLiveWalThroughWritableSidecars() throws Exception {
        Path path = directory.resolve("readonly-main.db");
        try (Connection engine = openWal(path); Statement writer = engine.createStatement()) {
            writer.execute("CREATE TABLE sample(value INTEGER NOT NULL)");
            writer.execute("INSERT INTO sample VALUES (10)");
            assertTrue(Files.isWritable(Path.of(path + "-wal")));
            assertTrue(Files.isWritable(Path.of(path + "-shm")));
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            config.setBusyTimeout(0);
            try (Connection reader = new JDBC().connect("jdbc:sqlite:" + path, config.toProperties());
                 Statement snapshot = reader.createStatement()) {
                assertEquals("normal", scalar(reader, "PRAGMA locking_mode"));
                assertEquals("wal", scalar(reader, "PRAGMA journal_mode"));
                assertEquals("10", scalar(reader, "SELECT value FROM sample"));
                SQLException readonly = assertThrows(SQLException.class,
                        () -> snapshot.execute("UPDATE sample SET value=99"));
                assertEquals(SQLITE_READONLY, readonly.getErrorCode() & 0xff);
                snapshot.execute("BEGIN");
                assertEquals("10", scalar(reader, "SELECT value FROM sample"));
                writer.execute("UPDATE sample SET value=15");
                assertEquals("10", scalar(reader, "SELECT value FROM sample"));
                snapshot.execute("ROLLBACK");
                assertEquals("15", scalar(reader, "SELECT value FROM sample"));
            }
            truncateWal(engine);
            assertEquals("15", scalar(engine, "SELECT value FROM sample"));
            assertEquals("ok", scalar(engine, "PRAGMA integrity_check"));
        }
    }
}
