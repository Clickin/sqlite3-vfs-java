import io.github.clickin.sqlitevfs.NioVfs;
import io.github.clickin.sqlitevfs.adapter.VfsBridge;
import io.github.clickin.sqlitevfs.engine.JvmVfsImports;
import io.roastedroot.sqlite4j.SQLiteConfig;
import io.roastedroot.sqlite4j.core.WasmDB;
import java.sql.*;
import java.util.concurrent.locks.LockSupport;
import static io.github.clickin.sqlitevfs.SqliteCodes.*;

/** Guest half of a power-cut experiment; the host must kill QEMU after HOLD. */
public class VmPowerCutPeer {
    private static void hold(String point) {
        System.out.println("HOLD " + point);
        System.out.flush();
        for (;;) LockSupport.park();
    }
    private static void execute(WasmDB db, String sql) throws Exception {
        int rc = db._exec(sql);
        if (rc != SQLITE_OK) throw new IllegalStateException("SQLite rc=" + rc);
    }
    public static void main(String[] args) throws Exception {
        String file = args[0], mode = args[1], action = args[2];
        String settings = "PRAGMA journal_mode=" + mode + "; PRAGMA synchronous="
                + (mode.equals("DELETE") ? "EXTRA" : "FULL") + "; PRAGMA cache_size=2;";
        if (action.equals("init") || action.equals("verify")) {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
                 Statement s = c.createStatement()) {
                if (action.equals("init")) {
                    for (String sql : settings.split(";")) if (!sql.isBlank()) s.execute(sql);
                    s.execute("CREATE TABLE ledger(id INTEGER PRIMARY KEY, value INTEGER NOT NULL, payload BLOB NOT NULL)");
                    s.execute("BEGIN IMMEDIATE");
                    s.execute("WITH RECURSIVE ids(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM ids WHERE i<64) INSERT INTO ledger SELECT i,1,zeroblob(16384) FROM ids");
                    s.execute("COMMIT");
                    System.out.println("ACK 1");
                } else {
                    boolean committed = Boolean.parseBoolean(args[3]);
                    try (ResultSet r = s.executeQuery("SELECT count(*),sum(value),sum(length(payload)),sum(CASE WHEN id<=64 THEN value ELSE 0 END) FROM ledger")) {
                        if (!r.next() || r.getLong(1)!=(committed?128:64) || r.getLong(2)!=(committed?192:64)
                                || r.getLong(3)!=(committed?2097152:1048576) || r.getLong(4)!=(committed?128:64))
                            throw new AssertionError("Lost committed transaction or partial transaction");
                    }
                    try (ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
                        if (!r.next() || !r.getString(1).equals("ok")) throw new AssertionError("Corrupt database");
                    }
                    System.out.println("VERIFIED committed=" + committed + " integrity=ok");
                }
            }
            return;
        }
        boolean[] armed = {false}, journalSynced = {false};
        VfsBridge.Observer observer = (operation, flags, path, offset, amount, result) -> {
            if (!armed[0]) return;
            boolean journal = (flags & SQLITE_OPEN_MAIN_JOURNAL) != 0;
            if (journal && operation.equals("before-sync") && action.equals("before-journal-sync")) hold(action);
            if (journal && operation.equals("after-sync") && result == SQLITE_OK) {
                journalSynced[0] = true;
                if (action.equals("after-journal-sync")) hold(action);
            }
            if (journalSynced[0] && (flags & SQLITE_OPEN_MAIN_DB) != 0
                    && operation.equals("after-write") && result == SQLITE_OK && action.equals("after-db-write")) hold(action);
        };
        SQLiteConfig config = new SQLiteConfig();
        WasmDB db = new WasmDB("jdbc:sqlite:" + file, file, config, new JvmVfsImports(new NioVfs(), observer));
        try {
            db.open(file, config.getOpenModeFlags());
            execute(db, settings);
            armed[0] = true;
            execute(db, "BEGIN IMMEDIATE; UPDATE ledger SET value=2; WITH RECURSIVE ids(i) AS (VALUES(65) UNION ALL SELECT i+1 FROM ids WHERE i<128) INSERT INTO ledger SELECT i,1,zeroblob(16384) FROM ids;");
            if (action.equals("before-commit")) hold(action);
            execute(db, "COMMIT");
            System.out.println("ACK 2");
            if (action.equals("after-commit")) hold(action);
            throw new AssertionError("Requested crash point not reached: " + action);
        } finally { db.close(); }
    }
}
