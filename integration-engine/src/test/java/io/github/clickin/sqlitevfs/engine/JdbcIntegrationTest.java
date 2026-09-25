package io.github.clickin.sqlitevfs.engine;

import io.github.clickin.sqlitevfs.JdkSupport;
import io.roastedroot.sqlite4j.BusyHandler;
import io.roastedroot.sqlite4j.Collation;
import io.roastedroot.sqlite4j.Function;
import io.roastedroot.sqlite4j.JDBC;
import io.roastedroot.sqlite4j.ProgressHandler;
import io.roastedroot.sqlite4j.SQLiteConfig;
import io.roastedroot.sqlite4j.SQLiteConnection;
import io.roastedroot.sqlite4j.SQLiteDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(90)
class JdbcIntegrationTest {
    @TempDir Path directory;

    private SQLiteConnection open(Path path) throws SQLException {
        // The native oracle is test-only; never let DriverManager choose its driver here.
        return (SQLiteConnection) new JDBC().connect("jdbc:sqlite:" + path, new Properties());
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    @Test
    void createsRealFileAndReopensAllBoundStorageClasses() throws Exception {
        Path path = directory.resolve("bindings.db");
        byte[] blob = {0, 1, (byte) 255, 0, 42};
        String text = "SQLite 한글 \u0000 tail";
        try (SQLiteConnection connection = open(path);
                Statement statement = connection.createStatement()) {
            statement.execute("create table data(n, i integer, r real, t text, b blob)");
            try (PreparedStatement insert = connection.prepareStatement("insert into data values(?,?,?,?,?)")) {
                insert.setNull(1, Types.NULL);
                insert.setLong(2, Long.MIN_VALUE + 17);
                insert.setDouble(3, 3.141592653589793);
                insert.setString(4, text);
                insert.setBytes(5, blob);
                assertEquals(1, insert.executeUpdate());
            }
            try (var stream = Files.newInputStream(path)) {
                assertArrayEquals("SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII),
                        stream.readNBytes(16));
            }
        }
        try (SQLiteConnection connection = open(path);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select * from data")) {
            assertTrue(rows.next());
            assertNull(rows.getObject(1));
            assertTrue(rows.wasNull());
            assertEquals(Long.MIN_VALUE + 17, rows.getLong(2));
            assertEquals(3.141592653589793, rows.getDouble(3));
            assertEquals(text, rows.getString(4));
            assertArrayEquals(blob, rows.getBytes(5));
            assertFalse(rows.next());
        }
    }

    @Test
    void commitRollbackAndSavepointHaveDurableVisibility() throws Exception {
        Path path = directory.resolve("transactions.db");
        try (SQLiteConnection writer = open(path);
                Statement statement = writer.createStatement()) {
            statement.execute("create table entries(id integer primary key)");
            writer.setAutoCommit(false);
            statement.executeUpdate("insert into entries values(1)");
            Savepoint savepoint = writer.setSavepoint("before_second");
            statement.executeUpdate("insert into entries values(2)");
            writer.rollback(savepoint);
            writer.releaseSavepoint(savepoint);
            writer.commit();
            statement.executeUpdate("insert into entries values(3)");
            writer.rollback();
            writer.setAutoCommit(true);
            try (SQLiteConnection reader = open(path)) {
                assertEquals(1, scalar(reader, "select sum(id) from entries"));
                assertEquals(1, scalar(reader, "select count(*) from entries"));
            }
        }
    }

    @Test
    void exposesMetadataAndAppliesConnectionConfiguration() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(37);
        config.enableAutomaticIndex(false);
        config.setCacheSpill(false);
        config.setWalAutocheckpoint(23);
        try (Connection connection = new JDBC().connect(
                "jdbc:sqlite:" + directory.resolve("metadata.db"), config.toProperties());
                Statement statement = connection.createStatement()) {
            statement.execute("create table parent(id integer primary key, name text not null)");
            statement.execute("create table child(parent_id references parent(id))");
            assertEquals(1, scalar(connection, "pragma foreign_keys"));
            assertEquals(37, scalar(connection, "pragma busy_timeout"));
            assertEquals(0, scalar(connection, "pragma automatic_index"));
            assertEquals(0, scalar(connection, "pragma cache_spill"));
            assertEquals(23, scalar(connection, "pragma wal_autocheckpoint"));
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate("insert into child values(999)"));
            assertEquals("SQLite", connection.getMetaData().getDatabaseProductName());
            assertEquals("3.53.4", connection.getMetaData().getDatabaseProductVersion());
            try (ResultSet columns = connection.getMetaData().getColumns(null, null, "parent", "name")) {
                assertTrue(columns.next());
                assertEquals("name", columns.getString("COLUMN_NAME"));
                assertEquals(Types.VARCHAR, columns.getInt("DATA_TYPE"));
            }
            try (ResultSet rows = statement.executeQuery("select id, name from parent")) {
                assertEquals(2, rows.getMetaData().getColumnCount());
                assertEquals("parent", rows.getMetaData().getTableName(1));
            }
        }
    }

    @Test
    void vacuumAttachAndMultipleConnectionsUseLiveHostFiles() throws Exception {
        Path main = directory.resolve("main.db");
        Path attached = directory.resolve("attached.db");
        try (SQLiteConnection first = open(main);
                Statement statement = first.createStatement()) {
            statement.execute("create table data(id integer primary key, value text)");
            statement.execute("insert into data values(1, 'visible')");
            try (SQLiteConnection second = open(main)) {
                assertEquals(1, scalar(second, "select count(*) from data"));
                try (Statement other = second.createStatement()) {
                    other.execute("insert into data values(2, 'shared')");
                }
                assertEquals(2, scalar(first, "select count(*) from data"));
            }
            try (PreparedStatement attach = first.prepareStatement("attach database ? as aux")) {
                attach.setString(1, attached.toString());
                attach.execute();
            }
            statement.execute("create table aux.copied as select * from main.data");
            statement.execute("detach database aux");
            statement.execute("pragma temp_store=FILE");
            statement.execute("create temp table scratch as select * from data");
            assertEquals(2, scalar(first, "select count(*) from scratch"));
            statement.execute("vacuum");
        }
        try (SQLiteConnection connection = open(attached)) {
            assertEquals(2, scalar(connection, "select count(*) from copied"));
        }
        try (SQLiteConnection connection = open(main);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("pragma integrity_check")) {
            assertTrue(rows.next());
            assertEquals("ok", rows.getString(1));
        }
    }

    @Test
    void backupAndRestoreUseSqliteOnlineBackupOnRealFiles() throws Exception {
        Path backup = directory.resolve("backup.db");
        try (SQLiteConnection connection = open(directory.resolve("source.db"));
                Statement statement = connection.createStatement()) {
            statement.execute("create table value(n integer)");
            statement.execute("insert into value values(41)");
            assertEquals(0, connection.getDatabase().backup("main", backup.toString(), null));
            statement.execute("update value set n=99");
            try (SQLiteConnection copy = open(backup)) {
                assertEquals(41, scalar(copy, "select n from value"));
            }
            assertEquals(0, connection.getDatabase().restore("main", backup.toString(), null));
            assertEquals(41, scalar(connection, "select n from value"));
            assertNotEquals(0, connection.getDatabase().restore("main",
                    directory.resolve("missing.db").toString(), null));
            assertEquals(41, scalar(connection, "select n from value"));
        }
    }

    @Test
    void callbacksRemainIsolatedAcrossEngineInstances() throws Exception {
        Path path = directory.resolve("callbacks.db");
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger progress = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        try (SQLiteConnection first = open(path);
                SQLiteConnection second = open(path);
                Statement statement = first.createStatement()) {
            Function.create(first, "identity_text", new Function() {
                @Override public void xFunc() throws SQLException { result(value_text(0)); }
            });
            Function.create(second, "identity_text", new Function() {
                @Override public void xFunc() throws SQLException { result("second"); }
            });
            Collation.create(first, "reverse_order", new Collation() {
                @Override public int xCompare(String left, String right) { return right.compareTo(left); }
            });
            ProgressHandler.setHandler(first, 1, new ProgressHandler() {
                @Override public int progress() { progress.incrementAndGet(); return 0; }
            });
            BusyHandler.setHandler(second, new BusyHandler() {
                @Override public int callback(int previous) { busy.incrementAndGet(); return 0; }
            });
            first.addUpdateListener((type, database, table, rowId) -> updates.incrementAndGet());
            statement.execute("create table data(value text)");
            statement.execute("insert into data values('alpha'),('omega')");
            try (ResultSet rows = statement.executeQuery(
                    "select identity_text(value) from data order by value collate reverse_order")) {
                assertTrue(rows.next());
                assertEquals("omega", rows.getString(1));
                assertTrue(rows.next());
                assertEquals("alpha", rows.getString(1));
            }
            try (Statement other = second.createStatement();
                    ResultSet rows = other.executeQuery("select identity_text('input')")) {
                assertTrue(rows.next());
                assertEquals("second", rows.getString(1));
            }
            statement.execute("begin immediate");
            try (Statement other = second.createStatement()) {
                SQLException error = assertThrows(SQLException.class,
                        () -> other.executeUpdate("insert into data values('blocked')"));
                assertEquals(5, error.getErrorCode() & 255);
                assertTrue(busy.get() > 0);
            } finally {
                statement.execute("rollback");
            }
            assertEquals(2, updates.get());
            assertTrue(progress.get() > 0);
            Function.destroy(first, "identity_text");
            assertThrows(SQLException.class,
                    () -> statement.executeQuery("select identity_text('gone')"));
            Collation.destroy(first, "reverse_order");
            ProgressHandler.clearHandler(first);
            BusyHandler.clearHandler(second);
        }
    }

    @Test
    void datasourceAppliesNumericSpillThreshold() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("datasource.db"));
        dataSource.setAutomaticIndex(false);
        dataSource.setCacheSize(10);
        dataSource.setCacheSpill(12);
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, scalar(connection, "pragma automatic_index"));
            assertEquals(12, scalar(connection, "pragma cache_spill"));
        }
        dataSource.setCacheSpill(false);
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, scalar(connection, "pragma cache_spill"));
        }
    }

    @ParameterizedTest(name = "concurrent cancellation, virtualThreads={0}")
    @ValueSource(booleans = {false, true})
    void cancellationDoesNotEnterGuestConcurrentlyOrLeakToNextQuery(boolean virtual) throws Exception {
        assumeTrue(!virtual || JdkSupport.hasVirtualThreads(), "Virtual threads require JDK 21 or later");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstCallback = new AtomicBoolean(true);
        ExecutorService executor = virtual ? JdkSupport.newVirtualThreadExecutor() : Executors.newSingleThreadExecutor();
        try (SQLiteConnection connection = open(directory.resolve("cancel.db"));
                Statement statement = connection.createStatement();
                AutoCloseable workers = () -> {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "query worker did not stop");
                }) {
            ProgressHandler.setHandler(connection, 1, new ProgressHandler() {
                @Override public int progress() throws SQLException {
                    if (firstCallback.compareAndSet(true, false)) {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new SQLException(interrupted);
                        }
                    }
                    return 0;
                }
            });
            var query = executor.submit(() -> {
                try (ResultSet rows = statement.executeQuery(
                        "with recursive n(x) as (values(1) union all select x+1 from n"
                                + " where x<1000000000) select sum(x) from n")) {
                    rows.next();
                    return (SQLException) null;
                } catch (SQLException interrupted) {
                    return interrupted;
                }
            });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                statement.cancel();
            } finally {
                release.countDown();
            }
            SQLException interrupted = query.get(10, TimeUnit.SECONDS);
            assertNotNull(interrupted);
            assertEquals(9, interrupted.getErrorCode() & 255);
            ProgressHandler.clearHandler(connection);
            assertEquals(42, scalar(connection, "select 42"));
            // An idle cancel must not abort the following statement.
            statement.cancel();
            assertEquals(43, scalar(connection, "select 43"));
        }
    }

    @Test
    void failedOpenAndUnsupportedNativeExtensionsDoNotPoisonLaterConnections() throws Exception {
        Path path = directory.resolve("extensions.db");
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> new JDBC().connect("jdbc:sqlite:" + path, config.toProperties()));
        assertThrows(SQLException.class, () -> open(directory.resolve("absent/sub/database.db")));
        try (SQLiteConnection connection = open(path)) {
            assertEquals(42, scalar(connection, "select 42"));
        }
    }
}
