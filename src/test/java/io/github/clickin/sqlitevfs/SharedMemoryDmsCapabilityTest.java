package io.github.clickin.sqlitevfs;

import io.github.clickin.sqlitevfs.RollbackFileTest.Child;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real-process evidence for the F_GETLK distinction required by unixLockSharedMemory. */
@Timeout(90)
class SharedMemoryDmsCapabilityTest {
    @TempDir Path directory;

    @Test
    void failedExclusiveThenGrantedSharedCannotDistinguishLiveReaderFromCrashedInitializer() throws Exception {
        Path database = directory.resolve("deadman.db");
        Path shm = Path.of(database + "-shm");
        byte[] liveHeader;
        try (Child nativeDb = Child.nativeDb(database, true)) {
            assertEquals("wal", nativeDb.ok("PRAGMA journal_mode=WAL"));
            nativeDb.ok("PRAGMA wal_autocheckpoint=0");
            nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
            try (FileChannel channel = FileChannel.open(shm, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                try (FileLock exclusive = channel.tryLock(128, 1, false)) {
                    assertNull(exclusive, "native SQLite holds its shared DMS lock");
                }
                try (FileLock shared = channel.tryLock(128, 1, true)) {
                    assertNotNull(shared);
                    assertTrue(shared.isShared());
                    liveHeader = header(channel);
                }
            }
            assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
        }
        // The native child was forcibly terminated, leaving the exact same
        // apparently valid index. The next initializer must reset it even if
        // checksum/header bytes happen to look valid (os_unix.c DMS contract).
        assertEquals(32768, Files.size(shm));
        try (FileChannel channel = FileChannel.open(shm, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            try (Child initializer = new Child(InitializerBeforeTruncate.class, shm.toString())) {
                try (FileLock exclusive = channel.tryLock(128, 1, false)) {
                    assertNull(exclusive, "initializer holds EXCLUSIVE rather than SHARED DMS");
                }
                try (FileLock blockedShared = channel.tryLock(128, 1, true)) {
                    assertNull(blockedShared, "before the crash, both lock modes are excluded");
                }
                // This is the precise scheduling gap between an unsuccessful
                // exclusive tryLock and the subsequent shared tryLock. No sleeps
                // or probabilistic race: Child.close waits for process death.
                initializer.close();
                try (FileLock shared = channel.tryLock(128, 1, true)) {
                    assertNotNull(shared);
                    assertTrue(shared.isShared());
                    assertArrayEquals(liveHeader, header(channel),
                            "shared acquisition did not prove initialization; stale bytes survive the crash gap");
                }
            }
            // The reference Unix path would have observed the EXCLUSIVE blocker
            // with F_GETLK and returned BUSY instead of accepting that index.
            // A later genuine first opener can now acquire exclusive and reset.
            try (FileLock firstOpener = channel.tryLock(128, 1, false)) {
                assertNotNull(firstOpener);
                channel.truncate(0);
                assertEquals(0, channel.size());
            }
        }
        System.out.println("SHM-DMS: live-shared and crash-before-truncate both yield exclusive=null, shared=granted; unchanged header cannot distinguish them");
    }

    private static byte[] header(FileChannel channel) throws Exception {
        ByteBuffer data = ByteBuffer.allocate(96);
        while (data.hasRemaining()) {
            int count = channel.read(data, data.position());
            assertTrue(count > 0, "complete pair of WAL-index headers required");
        }
        return data.array();
    }

    public static final class InitializerBeforeTruncate {
        public static void main(String[] args) throws Exception {
            try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock deadman = channel.tryLock(128, 1, false)) {
                assertNotNull(deadman);
                System.out.println("READY\tDMS-exclusive-before-truncate");
                System.out.flush();
                System.in.read();
                throw new AssertionError("Parent must kill initializer before truncation");
            }
        }
    }
}
