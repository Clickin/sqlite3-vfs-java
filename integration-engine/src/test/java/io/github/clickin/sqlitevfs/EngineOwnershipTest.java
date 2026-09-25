package io.github.clickin.sqlitevfs;

import io.roastedroot.sqlite4j.BusyHandler;
import io.roastedroot.sqlite4j.Function;
import io.roastedroot.sqlite4j.JDBC;
import io.roastedroot.sqlite4j.ProgressHandler;
import io.roastedroot.sqlite4j.SQLiteConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(90)
class EngineOwnershipTest {
    private static final int HEAP_LIMIT = 1024 * 1024;
    private static final String TOO_LARGE = "x".repeat(2 * HEAP_LIMIT);

    @TempDir Path directory;

    private static SQLiteConnection open(String filename) throws SQLException {
        return (SQLiteConnection) new JDBC().connect("jdbc:sqlite:" + filename, new Properties());
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static void seed(SQLiteConnection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table data(n integer)");
            statement.execute("insert into data values(41)");
        }
        assertEquals(HEAP_LIMIT, scalar(connection, "pragma hard_heap_limit=" + HEAP_LIMIT));
    }

    private static void assertNomem(Executable operation) {
        SQLException failure = assertThrows(SQLException.class, operation);
        assertEquals(7, failure.getErrorCode() & 255, failure.getMessage());
    }

    @Test
    void failedFilenameAllocationDoesNotRedirectBackupToTemporaryDatabase() throws Exception {
        Path source = directory.resolve("source.db");
        Path destination = directory.resolve("backup.db");
        try (SQLiteConnection connection = open(source.toString())) {
            seed(connection);
            // A valid URI whose ignored query parameter exceeds this engine's SQLite heap limit.
            String filename = destination.toUri() + "?unused=" + TOO_LARGE;
            assertNomem(() -> connection.getDatabase().backup("main", filename, null));
            assertFalse(Files.exists(destination));
            assertEquals(41, scalar(connection, "select n from data"));
            assertEquals(0, connection.getDatabase().backup("main", destination.toString(), null));
        }
        try (SQLiteConnection copy = open(destination.toString())) {
            assertEquals(41, scalar(copy, "select n from data"));
        }
    }

    @Test
    void failedSqlAndBindingAllocationsReportNomemWithoutChangingData() throws Exception {
        try (SQLiteConnection connection = open(":memory:")) {
            seed(connection);
            String sql = "/*" + TOO_LARGE + "*/ update data set n=99";
            assertNomem(() -> connection.getDatabase()._exec(sql));
            assertNomem(() -> connection.prepareStatement(sql));
            try (PreparedStatement insert = connection.prepareStatement("insert into data values(?)")) {
                insert.setString(1, TOO_LARGE);
                assertNomem(insert::executeUpdate);
                insert.setBytes(1, new byte[2 * HEAP_LIMIT]);
                assertNomem(insert::executeUpdate);
                insert.setInt(1, 42);
                assertEquals(1, insert.executeUpdate());
            }
            assertEquals(2, scalar(connection, "select count(*) from data"));
            assertEquals(83, scalar(connection, "select sum(n) from data"));
        }
    }

    @Test
    void udfAllocationFailuresReturnThroughSqliteAndLeaveCallbacksUsable() throws Exception {
        for (int kind = 0; kind < 4; kind++) {
            int resultKind = kind;
            try (SQLiteConnection connection = open(":memory:")) {
                seed(connection);
                Function.create(connection, "allocation_result", new Function() {
                    @Override public void xFunc() throws SQLException {
                        if (value_int(0) == 0) {
                            result(value_int(1) + 1);
                        } else if (resultKind == 0) {
                            result(TOO_LARGE);
                        } else if (resultKind == 1) {
                            result(new byte[2 * HEAP_LIMIT]);
                        } else if (resultKind == 2) {
                            error(TOO_LARGE);
                        } else {
                            connection.getDatabase()._exec("/*" + TOO_LARGE + "*/ select 1");
                            result(99);
                        }
                    }
                }, 2, 0);
                assertNomem(() -> scalar(connection, "select allocation_result(1, n) from data"));
                assertEquals(42, scalar(connection, "select allocation_result(0, n) from data"));
                assertEquals(41, scalar(connection, "select n from data"));
            }
        }
    }

    @Test
    void failingUdfsUnwindSqliteStatementsBeforeRethrowingOriginalExceptions() throws Exception {
        SQLException sqlFailure = new SQLException("Rejected by the application");
        IllegalStateException javaFailure = new IllegalStateException("Rejected Java callback");
        try (SQLiteConnection connection = open(":memory:")) {
            seed(connection);
            Function.create(connection, "reject_value", new Function() {
                @Override public void xFunc() throws SQLException {
                    if (value_int(0) == 1) {
                        throw sqlFailure;
                    }
                    if (value_int(0) == 2) {
                        throw javaFailure;
                    }
                    result(value_int(0));
                }
            }, 1, 0);
            for (int attempt = 0; attempt < 32; attempt++) {
                assertSame(sqlFailure, assertThrows(SQLException.class,
                        () -> connection.getDatabase()._exec(
                                "insert into data values(99),(reject_value(1))")));
                assertSame(javaFailure, assertThrows(IllegalStateException.class,
                        () -> connection.getDatabase()._exec(
                                "insert into data values(99),(reject_value(2))")));
                // Both failed inserts must roll back their first row, not leave a live C statement.
                assertEquals(1, scalar(connection, "select count(*) from data"));
                assertEquals(41, scalar(connection, "select reject_value(n) from data"));
            }
            connection.getDatabase()._exec("insert into data values(reject_value(42))");
            assertEquals(83, scalar(connection, "select sum(n) from data"));
        }
    }

    @Test
    void failingProgressCallbacksStopAndUnwindBeforeRethrowing() throws Exception {
        SQLException original = new SQLException("Stop progress");
        try (SQLiteConnection connection = open(":memory:")) {
            seed(connection);
            for (int attempt = 0; attempt < 16; attempt++) {
                ProgressHandler.setHandler(connection, 1, new ProgressHandler() {
                    @Override public int progress() throws SQLException { throw original; }
                });
                try {
                    assertSame(original, assertThrows(SQLException.class,
                            () -> connection.getDatabase()._exec("update data set n=n+1")));
                } finally {
                    ProgressHandler.clearHandler(connection);
                }
                assertEquals(41, scalar(connection, "select n from data"));
            }
            connection.getDatabase()._exec("update data set n=42");
            assertEquals(42, scalar(connection, "select n from data"));
        }
    }

    @Test
    void failingBusyCallbacksStopRetryingAndLeaveConnectionUsable() throws Exception {
        SQLException original = new SQLException("Stop waiting for the writer");
        Path path = directory.resolve("busy-callback.db");
        try (SQLiteConnection first = open(path.toString());
                SQLiteConnection second = open(path.toString())) {
            seed(first);
            BusyHandler.setHandler(second, new BusyHandler() {
                @Override public int callback(int previous) throws SQLException { throw original; }
            });
            first.getDatabase()._exec("begin immediate");
            try {
                for (int attempt = 0; attempt < 16; attempt++) {
                    assertSame(original, assertThrows(SQLException.class,
                            () -> second.getDatabase()._exec("update data set n=99")));
                    assertEquals(41, scalar(second, "select n from data"));
                }
            } finally {
                first.getDatabase()._exec("rollback");
                BusyHandler.clearHandler(second);
            }
            second.getDatabase()._exec("update data set n=42");
            assertEquals(42, scalar(first, "select n from data"));
        }
    }

    @Test
    void failedDeserializeAllocationPreservesTheExistingDatabase() throws Exception {
        try (SQLiteConnection connection = open(":memory:")) {
            seed(connection);
            assertNomem(() -> connection.deserialize("main", new byte[2 * HEAP_LIMIT]));
            assertEquals(41, scalar(connection, "select n from data"));
            byte[] image = connection.serialize("main");
            connection.deserialize("main", image);
            assertEquals(41, scalar(connection, "select n from data"));
        }
    }

    @Test
    void deserializeTransfersOwnershipOnBothSuccessAndSqliteFailure() throws Exception {
        try (SQLiteConnection connection = open(":memory:")) {
            seed(connection);
            connection.getDatabase()._exec("create table payload(b blob)");
            connection.getDatabase()._exec("insert into payload values(zeroblob(65536))");
            byte[] image = connection.serialize("main");
            // SQLite frees FREEONCLOSE input even when the target schema is invalid.
            // https://www.sqlite.org/c3ref/deserialize.html
            for (int attempt = 0; attempt < 32; attempt++) {
                SQLException failure = assertThrows(SQLException.class,
                        () -> connection.deserialize("temp", image));
                assertEquals(1, failure.getErrorCode() & 255);
                connection.deserialize("main", image);
                assertEquals(41, scalar(connection, "select n from data"));
            }
            connection.getDatabase()._exec("insert into payload values(zeroblob(65536))");
            assertEquals(131072, scalar(connection, "select sum(length(b)) from payload"));
        }
    }

    @Test
    void closedDeserializeDoesNotConsumeTheEngineHeap() throws Exception {
        try (SQLiteConnection connection = open(":memory:")) {
            seed(connection);
            connection.getDatabase()._exec("create table payload(b blob)");
            connection.getDatabase()._exec("insert into payload values(zeroblob(65536))");
            byte[] image = connection.serialize("main");
            connection.close();
            for (int attempt = 0; attempt < 32; attempt++) {
                SQLException failure = assertThrows(SQLException.class,
                        () -> connection.deserialize("main", image));
                assertNotEquals(7, failure.getErrorCode() & 255, "Closed lifecycle must win over allocation");
            }
            // Exercise the same allocator through the public DB lifecycle, not a fresh engine.
            connection.getDatabase().open(":memory:", 6);
            connection.deserialize("main", image);
            assertEquals(41, scalar(connection, "select n from data"));
            assertEquals(65536, scalar(connection, "select length(b) from payload"));
        }
    }

    @ParameterizedTest(name = "callback ownership, virtualThreads={0}")
    @ValueSource(booleans = {false, true})
    void functionCannotBeReboundToAnotherEngineEvenDuringAnActiveCallback(boolean virtual) throws Exception {
        assumeTrue(!virtual || JdkSupport.hasVirtualThreads(), "Virtual threads require JDK 21 or later");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = virtual ? JdkSupport.newVirtualThreadExecutor() : Executors.newFixedThreadPool(2);
        try (SQLiteConnection first = open(":memory:");
                SQLiteConnection second = open(":memory:");
                AutoCloseable workers = () -> {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "callback workers did not stop");
                }) {
            Function function = new Function() {
                @Override public void xFunc() throws SQLException {
                    if (value_int(0) == 10) {
                        entered.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new SQLException("Callback was not released");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new SQLException(interrupted);
                        }
                    }
                    result(value_int(0) + 1);
                }
            };
            Function.create(first, "increment", function, 1, 0);
            var active = executor.submit(() -> scalar(first, "select increment(10)"));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var registration = executor.submit(() -> assertThrows(SQLException.class,
                        () -> Function.create(second, "increment", function, 1, 0)));
                registration.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            assertEquals(11, active.get(10, TimeUnit.SECONDS));
            assertEquals(42, scalar(first, "select increment(41)"));
            assertThrows(SQLException.class, () -> scalar(second, "select increment(41)"));
            Function.create(second, "increment", new Function() {
                @Override public void xFunc() throws SQLException { result(value_int(0) + 2); }
            }, 1, 0);
            assertEquals(43, scalar(second, "select increment(41)"));
            Function.create(first, "increment_alias", function, 1, 0);
            assertEquals(42, scalar(first, "select increment_alias(41)"));
        }
    }
}
