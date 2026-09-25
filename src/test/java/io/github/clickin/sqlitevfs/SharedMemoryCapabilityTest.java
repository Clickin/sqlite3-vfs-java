package io.github.clickin.sqlitevfs;

import io.github.clickin.sqlitevfs.RollbackFileTest.Child;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Measures native SHM primitives, not an implemented SQLite WAL VFS. */
@Timeout(90)
class SharedMemoryCapabilityTest {
    @TempDir Path directory;

    @Test
    void nativeWalIndexVisibilityAndLocksAreProbedInAnIsolatedJvm() throws Exception {
        // Child exit is the only portable deterministic release of a MappedByteBuffer.
        try (Child probe = new Child(Probe.class, directory.resolve("wal.db").toString())) {
            // Probe emits READY only after its native visibility/locking assertions.
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            PrintStream protocol = System.out;
            System.setOut(System.err);
            Path database = Path.of(args[0]);
            Path shm = Path.of(args[0] + "-shm");
            ByteBuffer mapping;
            try (Child nativeDb = Child.nativeDb(database, true)) {
                assertEquals("wal", nativeDb.ok("PRAGMA journal_mode=WAL"));
                nativeDb.ok("PRAGMA wal_autocheckpoint=0");
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                try (FileChannel channel = FileChannel.open(shm, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    mapping = channel.map(FileChannel.MapMode.READ_WRITE, 0, 32768).order(ByteOrder.nativeOrder());
                    // SQLite walformat.html: iVersion at0, iChange at8, WAL_WRITE_LOCK at120.
                    assertEquals(3007000, mapping.getInt(0));
                    int change = mapping.getInt(8);
                    nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                    VarHandle.acquireFence();
                    assertEquals(change + 1, mapping.getInt(8));
                    try (FileLock writer = channel.tryLock(120, 1, false)) {
                        assertNotNull(writer);
                        nativeDb.busy("BEGIN IMMEDIATE");
                    }
                    nativeDb.ok("BEGIN IMMEDIATE");
                    try (FileLock conflict = channel.tryLock(120, 1, false)) {
                        assertNull(conflict, "Java SHM lock must observe the native WAL writer");
                    }
                    nativeDb.ok("ROLLBACK");
                    assertEquals("32", nativeDb.ok("SELECT sum(value) FROM sample"));
                }
            }
            String deletion;
            try {
                Files.delete(shm);
                deletion = "completed";
            } catch (IOException failure) {
                deletion = failure.getClass().getSimpleName() + ":" + failure.getMessage();
            }
            Reference.reachabilityFence(mapping);
            protocol.println("READY\twal-primitives\tnative-index-visible\ttwo-way-write-lock\tdelete-live-mapping=" + deletion);
            protocol.flush();
            System.in.read();
            Reference.reachabilityFence(mapping);
        }
    }
}
