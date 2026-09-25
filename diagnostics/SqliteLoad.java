import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded work, not an arbitrary wall-clock soak. Run against either JDBC artifact. */
public class SqliteLoad {
    static Connection open(Path db) throws Exception {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=1000");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA wal_autocheckpoint=128");
        } catch (Exception e) { c.close(); throw e; }
        return c;
    }
    static long scalar(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            if (!r.next()) throw new AssertionError("Missing query result");
            return r.getLong(1);
        }
    }
    static void valid(Connection c) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT count(*),sum(value+inverse) FROM accounts")) {
            if (!r.next() || r.getInt(1)!=128 || r.getLong(2)!=0)
                throw new AssertionError("Partial transaction observed");
        }
    }
    static long descriptors() {
        Object os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.UnixOperatingSystemMXBean
                ? ((com.sun.management.UnixOperatingSystemMXBean)os).getOpenFileDescriptorCount() : -1;
    }
    static long mappings() {
        long count=0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            if (pool.getName().startsWith("mapped")) count+=pool.getCount();
        return count;
    }
    static void churn(Path db, int count) throws Exception {
        for (int i=0;i<count;i++) try (Connection c=open(db)) { valid(c); }
    }
    public static void main(String[] args) throws Exception {
        int commits=args.length==0?1000:Integer.parseInt(args[0]);
        if (commits<1) throw new IllegalArgumentException("commits must be positive");
        Path directory=Files.createTempDirectory("sqlite-vfs-load-");
        Path db=directory.resolve("load.db");
        long begin=System.nanoTime();
        try {
            try (Connection c=open(db); Statement s=c.createStatement()) {
                try (ResultSet r=s.executeQuery("PRAGMA journal_mode=WAL")) {
                    if (!r.next() || !r.getString(1).equals("wal")) throw new AssertionError("WAL unavailable");
                }
                s.execute("CREATE TABLE accounts(id INTEGER PRIMARY KEY,value INTEGER NOT NULL,inverse INTEGER NOT NULL)");
                s.execute("WITH RECURSIVE ids(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM ids WHERE i<128) INSERT INTO accounts SELECT i,0,0 FROM ids");
                s.execute("CREATE TABLE commits(id INTEGER PRIMARY KEY)");
            }
            churn(db,16); // Warm class loading before observing resource lifetimes.
            long initialFds=descriptors(), initialMaps=mappings();
            List<Connection> connections=new ArrayList<>();
            ExecutorService pool=Executors.newFixedThreadPool(4);
            AtomicBoolean done=new AtomicBoolean();
            CyclicBarrier start=new CyclicBarrier(4);
            long reads=0;
            try {
                for(int i=0;i<4;i++) { Connection c=open(db);connections.add(c);valid(c); }
                List<Future<Long>> tasks=new ArrayList<>();
                tasks.add(pool.submit(() -> {
                    Connection c=connections.get(0);
                    start.await(30,TimeUnit.SECONDS);
                    try (Statement s=c.createStatement()) {
                        for(int i=1;i<=commits;i++) {
                            s.execute("BEGIN IMMEDIATE");
                            s.execute("UPDATE accounts SET value=value+1,inverse=inverse-1");
                            s.execute("INSERT INTO commits VALUES("+i+")");
                            s.execute("COMMIT");
                        }
                        return (long)commits;
                    } finally { done.set(true); }
                }));
                for(int i=1;i<4;i++) {
                    final Connection c=connections.get(i);
                    tasks.add(pool.submit(() -> {
                        start.await(30,TimeUnit.SECONDS);
                        long count=0;
                        do { valid(c);count++; } while(!done.get());
                        return count;
                    }));
                }
                if(tasks.get(0).get(120,TimeUnit.SECONDS)!=commits) throw new AssertionError();
                for(int i=1;i<tasks.size();i++) reads+=tasks.get(i).get(30,TimeUnit.SECONDS);
                if(scalar(connections.get(0),"SELECT count(*) FROM commits")!=commits
                        || scalar(connections.get(0),"SELECT sum(value) FROM accounts")!=128L*commits)
                    throw new AssertionError("Committed data lost");
            } finally {
                done.set(true);
                pool.shutdown();
                if(!pool.awaitTermination(30,TimeUnit.SECONDS)) throw new AssertionError("Workers did not exit");
                for(Connection c:connections)c.close();
            }
            for(int wave=1;wave<=4;wave++) {
                churn(db,50);
                long fds=descriptors(),maps=mappings();
                System.out.println("LIFETIME wave="+wave+" reopen_close="+(wave*50)+" fd_before="+initialFds+" fd_after="+fds+" mapped_before="+initialMaps+" mapped_after="+maps);
                if(initialFds>=0 && fds>initialFds+2) throw new AssertionError("File descriptor growth");
                if(maps>initialMaps) throw new AssertionError("Mapped region growth");
            }
            try(Connection c=open(db);Statement s=c.createStatement()) {
                try(ResultSet r=s.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                    if(!r.next() || r.getInt(1)!=0)throw new AssertionError("Checkpoint busy after readers closed");
                }
                try(ResultSet r=s.executeQuery("PRAGMA integrity_check")) {
                    if(!r.next() || !r.getString(1).equals("ok"))throw new AssertionError("Integrity failure");
                }
                if(scalar(c,"SELECT count(*) FROM commits")!=commits)throw new AssertionError("Reopen lost commits");
            }
            System.out.println("LOAD PASS java="+Runtime.version()+" commits="+commits+" reader_snapshots="+reads+" churn_connections=216 elapsed_ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin));
            if(initialFds<0)System.out.println("FD count unavailable on this OS; mapping counts and final file deletion still checked");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(directory)) {
                for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);
            }
        }
    }
}
