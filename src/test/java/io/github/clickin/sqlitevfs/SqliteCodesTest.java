package io.github.clickin.sqlitevfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteOpenMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteCodesTest {
    @TempDir
    Path directory;

    @Test
    void resultAndOpenAbiMatchesThePinnedIndependentOracle() throws Exception {
        for (SQLiteErrorCode code : SQLiteErrorCode.values()) {
            if (code != SQLiteErrorCode.UNKNOWN_ERROR) {
                assertEquals(code.code, SqliteCodes.class.getField(code.name()).getInt(null), code.name());
            }
        }
        for (SQLiteOpenMode mode : SQLiteOpenMode.values()) {
            String name = mode.name().startsWith("OPEN_") ? "SQLITE_" + mode.name() : "SQLITE_OPEN_" + mode.name();
            assertEquals(mode.flag, SqliteCodes.class.getField(name).getInt(null), name);
        }
    }

    @Test
    void nativeOracleHasTheExactPinnedSourceIdentity() throws Exception {
        Properties reference = new Properties();
        try (var input = Files.newInputStream(Path.of("reference-sqlite.properties"))) {
            reference.load(input);
        }
        try (var oracle = RollbackFileTest.Child.nativeDb(directory.resolve("reference.db"), true)) {
            assertEquals(reference.getProperty("sqlite.version"), oracle.ok("SELECT sqlite_version()"));
            assertEquals(reference.getProperty("sqlite.source-id"), oracle.ok("SELECT sqlite_source_id()"));
        }
    }
}
