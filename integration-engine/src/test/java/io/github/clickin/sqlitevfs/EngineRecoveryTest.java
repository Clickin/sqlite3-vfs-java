package io.github.clickin.sqlitevfs;

import io.github.clickin.sqlitevfs.RollbackFileTest.Child;
import io.github.clickin.sqlitevfs.adapter.VfsBridge;
import io.github.clickin.sqlitevfs.engine.JvmVfsImports;
import io.roastedroot.sqlite4j.SQLiteConfig;
import io.roastedroot.sqlite4j.core.WasmDB;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.clickin.sqlitevfs.SqliteCodes.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
class EngineRecoveryTest {
    @TempDir Path directory;

    private static void initialize(Path path) throws Exception {
        try (Child nativeDb = Child.nativeDb(path, true)) {
            nativeDb.ok("CREATE TABLE payload(id INTEGER PRIMARY KEY, bytes BLOB NOT NULL)");
            nativeDb.ok("INSERT INTO payload VALUES(1,zeroblob(65536))");
        }
    }

    @ParameterizedTest(name = "native recovery after JVM kill at {0}")
    @ValueSource(strings = {"before-journal-sync", "after-journal-sync", "after-db-write", "after-commit"})
    void nativeSqliteRecoversJvmEngineCrashesAtRealVfsEvents(String point) throws Exception {
        Path database = directory.resolve(point + ".db");
        initialize(database);
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        if (Runtime.version().feature() >= 24) command.add("--illegal-native-access=deny");
        command.addAll(List.of("-cp", cp, CrashPeer.class.getName(), database.toString(), point));
        Path log = directory.resolve(point + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(75, TimeUnit.SECONDS), "crash child did not reach target");
            String output = Files.readString(log);
            System.out.println("CRASH_EVIDENCE " + point + "\n" + output);
            assertEquals(90, process.exitValue(), output);
            assertTrue(output.contains("CRASH_POINT=" + point), output);
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
        if (point.equals("after-db-write")) {
            Path journal = Path.of(database + "-journal");
            assertTrue(Files.exists(journal), "dirty DB must retain its hot rollback journal");
            assertTrue(Files.size(journal) > 512);
        }
        String expected = point.equals("after-commit") ? "120" : "30";
        try (Child nativeDb = Child.nativeDb(database, false)) {
            assertEquals(expected, nativeDb.ok("SELECT sum(value) FROM sample"));
            assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
        }
        try (Connection engine = EngineNativeTest.open(database)) {
            assertEquals(expected, EngineNativeTest.scalar(engine, "SELECT sum(value) FROM sample"));
            assertEquals("ok", EngineNativeTest.scalar(engine, "PRAGMA integrity_check"));
        }
    }

    @Test
    void jvmEngineRecoversHotJournalProducedByKilledNativeWriter() throws Exception {
        Path database = directory.resolve("native-hot.db");
        initialize(database);
        byte[] before = Files.readAllBytes(database);
        try (Child writer = Child.nativeDb(database, false)) {
            writer.ok("PRAGMA cache_size=1");
            writer.ok("BEGIN IMMEDIATE");
            writer.ok("UPDATE payload SET bytes=randomblob(131072) WHERE id=1");
            writer.ok("UPDATE sample SET value=100 WHERE id=1");
            assertTrue(Files.size(Path.of(database + "-journal")) > 512);
            assertFalse(Arrays.equals(before, Files.readAllBytes(database)), "native fixture must really spill dirty database pages");
            // Child.close kills the uncommitted process rather than issuing ROLLBACK.
        }
        try (Connection engine = EngineNativeTest.open(database)) {
            assertEquals("30", EngineNativeTest.scalar(engine, "SELECT sum(value) FROM sample"));
            assertEquals("65536", EngineNativeTest.scalar(engine, "SELECT length(bytes) FROM payload"));
            assertEquals("ok", EngineNativeTest.scalar(engine, "PRAGMA integrity_check"));
        }
        try (Child nativeDb = Child.nativeDb(database, false)) {
            assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
            assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
        }
    }

    public static final class CrashPeer {
        public static void main(String[] args) throws Exception {
            String target = args[1];
            boolean[] journalSynced = {false};
            VfsBridge.Observer observer = (operation, flags, path, offset, amount, result) -> {
                boolean journal = (flags & SQLITE_OPEN_MAIN_JOURNAL) != 0;
                if (journal && operation.equals("before-sync") && target.equals("before-journal-sync")) halt(target);
                if (journal && operation.equals("after-sync") && result == SQLITE_OK) {
                    journalSynced[0] = true;
                    if (target.equals("after-journal-sync")) halt(target);
                }
                if (journalSynced[0] && (flags & SQLITE_OPEN_MAIN_DB) != 0
                        && operation.equals("after-write") && result == SQLITE_OK && target.equals("after-db-write")) halt(target);
            };
            SQLiteConfig config = new SQLiteConfig();
            WasmDB database = new WasmDB("jdbc:sqlite:" + args[0], args[0], config,
                    new JvmVfsImports(new NioVfs(), observer));
            try {
                database.open(args[0], config.getOpenModeFlags());
                execute(database, "PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; PRAGMA cache_size=2;");
                execute(database, "BEGIN IMMEDIATE; UPDATE sample SET value=100 WHERE id=1;"
                        + " UPDATE payload SET bytes=zeroblob(131072) WHERE id=1; COMMIT;");
                if (target.equals("after-commit")) halt(target);
                throw new AssertionError("VFS crash point was never reached: " + target);
            } finally {
                database.close();
            }
        }

        private static void execute(WasmDB database, String sql) throws Exception {
            int code = database._exec(sql);
            if (code != SQLITE_OK) throw new IllegalStateException("SQLite rc=" + code + " sql=" + sql);
        }

        private static void halt(String target) {
            System.out.println("CRASH_POINT=" + target);
            System.out.flush();
            Runtime.getRuntime().halt(90);
        }
    }
}
