package io.github.clickin.sqlitevfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.clickin.sqlitevfs.SqliteCodes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NioVfsTest {
    private static final int RW = SQLITE_OPEN_READWRITE | SQLITE_OPEN_MAIN_DB;
    private static final int CREATE = RW | SQLITE_OPEN_CREATE;
    private static final int TEMP = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE
            | SQLITE_OPEN_DELETEONCLOSE | SQLITE_OPEN_TEMP_DB;
    @TempDir
    Path directory;

    private static NioVfs vfs(FileSystemOps fs, RollbackFile.ChannelOpener channels) {
        return new NioVfs(fs, channels, Clock.systemUTC(), new Random(7));
    }

    private static NioVfs.File opened(NioVfs vfs, Path path, int flags) {
        NioVfs.OpenResult result = vfs.open(path == null ? null : path.toString(), flags);
        assertEquals(SQLITE_OK, result.code(), vfs.lastError());
        assertEquals(flags, result.flags());
        assertNotNull(result.file());
        return result.file();
    }

    @Test
    void createAndExclusiveAreAtomicAndNeverTruncate() throws Exception {
        NioVfs vfs = new NioVfs();
        Path path = directory.resolve("open.db");
        assertEquals(SQLITE_CANTOPEN, vfs.open(path.toString(), RW).code());
        assertFalse(Files.exists(path));
        NioVfs.File original = opened(vfs, path, CREATE | SQLITE_OPEN_EXCLUSIVE);
        try {
            assertEquals(SQLITE_OK, original.write(ByteBuffer.wrap(new byte[] {1, 2, 3}), 0));
            assertEquals(SQLITE_CANTOPEN, vfs.open(path.toString(), CREATE | SQLITE_OPEN_EXCLUSIVE).code());
            NioVfs.File second = opened(vfs, path, CREATE);
            try {
                assertEquals(new NioVfs.LongResult(SQLITE_OK, 3), second.fileSize());
            } finally {
                assertEquals(SQLITE_OK, second.close());
            }
        } finally {
            assertEquals(SQLITE_OK, original.close());
        }
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(path));
    }

    @ParameterizedTest
    @ValueSource(ints = {SQLITE_OPEN_MAIN_DB, SQLITE_OPEN_TEMP_DB, SQLITE_OPEN_TRANSIENT_DB,
            SQLITE_OPEN_MAIN_JOURNAL, SQLITE_OPEN_TEMP_JOURNAL, SQLITE_OPEN_SUBJOURNAL,
            SQLITE_OPEN_SUPER_JOURNAL, SQLITE_OPEN_WAL})
    void objectTypesRemainRealFiles(int type) throws Exception {
        Path path = directory.resolve("type-" + type);
        NioVfs.File file = opened(new NioVfs(), path, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | type);
        try {
            assertEquals(SQLITE_OK, file.write(ByteBuffer.wrap(new byte[] {42}), 0));
        } finally {
            assertEquals(SQLITE_OK, file.close());
        }
        assertArrayEquals(new byte[] {42}, Files.readAllBytes(path));
    }

    @Test
    void fallbackReportsActualAccessAndCannotWrite() throws Exception {
        Path path = directory.resolve("readonly.db");
        Files.write(path, new byte[] {4, 5, 6});
        NioVfs vfs = vfs(FileSystemOps.SYSTEM, (name, options) -> {
            if (Arrays.asList(options).contains(StandardOpenOption.WRITE)) {
                throw new AccessDeniedException(name.toString());
            }
            return FileChannel.open(name, options);
        });
        NioVfs.OpenResult result = vfs.open(path.toString(), CREATE | SQLITE_OPEN_URI);
        assertEquals(SQLITE_OK, result.code(), vfs.lastError());
        assertEquals(SQLITE_OPEN_READONLY | SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_URI, result.flags());
        try {
            ByteBuffer data = ByteBuffer.allocate(3);
            assertEquals(SQLITE_OK, result.file().read(data, 0));
            assertArrayEquals(new byte[] {4, 5, 6}, data.array());
            assertEquals(SQLITE_READONLY, result.file().write(ByteBuffer.wrap(new byte[] {9}), 0));
            assertEquals(SQLITE_READONLY, result.file().truncate(0));
            assertEquals(SQLITE_OK, result.file().lock(SQLITE_LOCK_SHARED));
            assertEquals(SQLITE_READONLY, result.file().lock(SQLITE_LOCK_RESERVED));
        } finally {
            assertEquals(SQLITE_OK, result.file().close());
        }
        assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(path));
    }

    @Test
    void temporaryFilesArePrivateAndDeletedAndFailedOpenCleansThem() throws Exception {
        MetadataFaults fs = new MetadataFaults();
        NioVfs vfs = vfs(fs, FileChannel::open);
        NioVfs.File temp = opened(vfs, null, TEMP | SQLITE_OPEN_EXCLUSIVE);
        Path created = fs.temp;
        try {
            assertTrue(Files.exists(created));
            if (Files.getFileStore(created).supportsFileAttributeView("posix")) {
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(created));
            }
            assertEquals(SQLITE_OK, temp.write(ByteBuffer.wrap(new byte[] {8}), 0));
        } finally {
            assertEquals(SQLITE_OK, temp.close());
            assertEquals(SQLITE_OK, temp.close());
        }
        assertFalse(Files.exists(created));
        try (FaultChannels channels = new FaultChannels()) {
            channels.fail(FaultChannels.Operation.OPEN, 1);
            NioVfs failing = vfs(fs, channels);
            assertEquals(SQLITE_CANTOPEN, failing.open(null, TEMP).code());
            assertFalse(Files.exists(fs.temp));
            fs.fail(MetadataFaults.Op.TEMP, 1, new AccessDeniedException("temporary directory"));
            assertEquals(SQLITE_CANTOPEN, failing.open(null, TEMP).code());
        }
    }

    @Test
    void metadataFailureAfterCreateClosesDescriptorAndRemovesOnlyCreatedFile() throws Exception {
        Path path = directory.resolve("failed-create");
        MetadataFaults fs = new MetadataFaults();
        fs.fail(MetadataFaults.Op.REAL, 1, new IOException("identity failure"));
        NioVfs vfs = vfs(fs, FileChannel::open);
        assertEquals(SQLITE_CANTOPEN, vfs.open(path.toString(), CREATE | SQLITE_OPEN_EXCLUSIVE).code());
        assertFalse(Files.exists(path));
        assertTrue(vfs.lastError().contains("identity failure"));
        NioVfs.File retry = opened(vfs, path, CREATE | SQLITE_OPEN_EXCLUSIVE);
        assertEquals(SQLITE_OK, retry.close());
        fs.fail(MetadataFaults.Op.ATTR, 2, new IOException("second metadata operation"));
        assertEquals(SQLITE_CANTOPEN, vfs.open(path.toString(), RW).code());
        assertTrue(Files.exists(path), "Failed opening an existing file must not delete it");
        assertTrue(vfs.lastError().contains("second metadata operation"));
    }

    @Test
    void cleanupErrorsRemainVisibleAndNeverPublishAHalfOpenHandle() throws Exception {
        MetadataFaults fs = new MetadataFaults();
        fs.fail(MetadataFaults.Op.REAL, 1, new IOException("registration failure"));
        fs.fail(MetadataFaults.Op.DELETE, 1, new AccessDeniedException("cleanup denied"));
        Path path = directory.resolve("cleanup-failure");
        NioVfs vfs = vfs(fs, FileChannel::open);
        NioVfs.OpenResult result = vfs.open(path.toString(), CREATE | SQLITE_OPEN_EXCLUSIVE);
        assertEquals(SQLITE_CANTOPEN, result.code());
        assertNull(result.file());
        assertEquals(0, result.flags());
        assertTrue(vfs.lastError().contains("registration failure"));
        assertTrue(vfs.lastError().contains("cleanup denied"));
        Files.delete(path);
        assertFalse(Files.exists(path));
    }

    @Test
    void disappearanceBeforeExistingChannelOpenDoesNotRecreateWithoutCreate() throws Exception {
        Path path = directory.resolve("disappearing");
        Files.write(path, new byte[] {1});
        NioVfs vfs = vfs(FileSystemOps.SYSTEM, (name, options) -> {
            Files.delete(name);
            return FileChannel.open(name, options);
        });
        assertEquals(SQLITE_CANTOPEN, vfs.open(path.toString(), RW).code());
        assertFalse(Files.exists(path));
    }

    @Test
    void pathsResolveSymlinksBeforeDotDotAndAllowMissingFinalComponents() throws Exception {
        Path root = directory.toRealPath();
        Path child = Files.createDirectories(root.resolve("nested/child"));
        Path alias = root.resolve("alias");
        try {
            Files.createSymbolicLink(alias, child);
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symlink creation unavailable: " + unavailable);
        }
        NioVfs vfs = new NioVfs();
        assertEquals(new NioVfs.PathResult(SQLITE_OK, root.resolve("nested/missing/deep.db").toString()),
                vfs.fullPathname(alias.resolve("../missing/deep.db").toString()));
        assertEquals(SQLITE_CANTOPEN_SYMLINK,
                vfs.open(alias.resolve("new.db").toString(), CREATE | SQLITE_OPEN_NOFOLLOW).code());
        Path dangling = root.resolve("dangling");
        Files.createSymbolicLink(dangling, root.resolve("absent"));
        assertEquals(SQLITE_CANTOPEN, vfs.open(dangling.toString(), CREATE | SQLITE_OPEN_EXCLUSIVE).code());
        assertFalse(Files.exists(root.resolve("absent")));
        assertEquals(root.resolve("absent").toString(), vfs.fullPathname(dangling.toString()).path());
        Path loop = root.resolve("loop");
        Files.createSymbolicLink(loop, loop);
        assertEquals(SQLITE_CANTOPEN_FULLPATH, vfs.fullPathname(loop.toString()).code());
    }

    @Test
    void accessIsMetadataOnlyAndPreservesNativeContention() throws Exception {
        Path path = directory.resolve("access.db");
        try (var nativeDb = RollbackFileTest.Child.nativeDb(path, true)) {
            NioVfs vfs = new NioVfs();
            NioVfs.File owner = opened(vfs, path, RW);
            try {
                assertEquals(SQLITE_OK, owner.lock(SQLITE_LOCK_SHARED));
                assertEquals(SQLITE_OK, owner.lock(SQLITE_LOCK_RESERVED));
                for (int flag : new int[] {SQLITE_ACCESS_EXISTS, SQLITE_ACCESS_READ, SQLITE_ACCESS_READWRITE}) {
                    assertEquals(new NioVfs.IntResult(SQLITE_OK, 1), vfs.access(path.toString(), flag));
                    nativeDb.busy("BEGIN IMMEDIATE");
                }
                assertEquals(new NioVfs.LongResult(SQLITE_OK, SQLITE_LOCK_RESERVED),
                        owner.fileControl(SQLITE_FCNTL_LOCKSTATE, 0));
                assertEquals(new NioVfs.IntResult(SQLITE_OK, 1), owner.checkReservedLock());
            } finally {
                assertEquals(SQLITE_OK, owner.close());
            }
            nativeDb.ok("BEGIN IMMEDIATE");
            nativeDb.ok("ROLLBACK");
        }
    }

    @Test
    void accessDistinguishesEmptyMissingDeniedAndIoFailure() throws Exception {
        MetadataFaults fs = new MetadataFaults();
        NioVfs vfs = vfs(fs, FileChannel::open);
        Path path = directory.resolve("access");
        assertEquals(new NioVfs.IntResult(SQLITE_OK, 0), vfs.access(path.toString(), SQLITE_ACCESS_EXISTS));
        Files.createFile(path);
        assertEquals(new NioVfs.IntResult(SQLITE_OK, 0), vfs.access(path.toString(), SQLITE_ACCESS_EXISTS));
        Files.write(path, new byte[] {1});
        assertEquals(new NioVfs.IntResult(SQLITE_OK, 1), vfs.access(path.toString(), SQLITE_ACCESS_EXISTS));
        fs.fail(MetadataFaults.Op.ACCESS, 1, new AccessDeniedException(path.toString()));
        assertEquals(new NioVfs.IntResult(SQLITE_OK, 0), vfs.access(path.toString(), SQLITE_ACCESS_READWRITE));
        fs.fail(MetadataFaults.Op.ATTR, 1, new IOException("metadata IO"));
        assertEquals(new NioVfs.IntResult(SQLITE_IOERR_ACCESS, 0), vfs.access(path.toString(), SQLITE_ACCESS_EXISTS));
        fs.fail(MetadataFaults.Op.ATTR, 1, new AccessDeniedException(path.toString()));
        assertEquals(SQLITE_IOERR_ACCESS, vfs.access(path.toString(), SQLITE_ACCESS_EXISTS).code());
        fs.fail(MetadataFaults.Op.ATTR, 1, new NoSuchFileException(path.toString()));
        assertEquals(new NioVfs.IntResult(SQLITE_OK, 0), vfs.access(path.toString(), SQLITE_ACCESS_EXISTS));
    }

    @Test
    void shortReadPartialWriteAndLargeOffsetsRetainTheirExactResults() throws Exception {
        Path path = directory.resolve("io");
        try (FaultChannels channels = new FaultChannels()) {
            NioVfs vfs = vfs(FileSystemOps.SYSTEM, channels);
            NioVfs.File file = opened(vfs, path, CREATE);
            try {
                channels.partial(2, 2);
                channels.fail(FaultChannels.Operation.WRITE, 2);
                assertEquals(SQLITE_IOERR_WRITE, file.write(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 0));
                ByteBuffer read = ByteBuffer.wrap(new byte[] {9, 9, 9, 9, 9, 9});
                read.position(1).limit(5);
                assertEquals(SQLITE_IOERR_SHORT_READ, file.read(read, 0));
                assertArrayEquals(new byte[] {9, 1, 2, 0, 0, 9}, read.array());
                channels.fail(FaultChannels.Operation.READ, 1);
                ByteBuffer failed = ByteBuffer.wrap(new byte[] {7});
                assertEquals(SQLITE_IOERR_READ, file.read(failed, 0));
                assertEquals(0, failed.position());
                assertEquals(7, failed.get(0));
                long offset = (1L << 32) + 17;
                assertEquals(SQLITE_OK, file.write(ByteBuffer.wrap(new byte[] {42}), offset));
                assertEquals(new NioVfs.LongResult(SQLITE_OK, offset + 1), file.fileSize());
                ByteBuffer last = ByteBuffer.allocate(1);
                assertEquals(SQLITE_OK, file.read(last, offset));
                assertEquals(42, last.get(0));
                assertEquals(SQLITE_OK, file.truncate(1));
                assertEquals(SQLITE_OK, file.truncate(3));
                ByteBuffer extended = ByteBuffer.allocate(3);
                assertEquals(SQLITE_OK, file.read(extended, 0));
                assertArrayEquals(new byte[] {1, 0, 0}, extended.array());
                channels.fail(FaultChannels.Operation.TRUNCATE, 1);
                assertEquals(SQLITE_IOERR_TRUNCATE, file.truncate(0));
                channels.fail(FaultChannels.Operation.SIZE, 1);
                assertEquals(SQLITE_IOERR_FSTAT, file.fileSize().code());
            } finally {
                assertEquals(SQLITE_OK, file.close());
            }
        }
        assertArrayEquals(new byte[] {1, 0, 0}, Files.readAllBytes(path));
    }

    @Test
    void syncAndDeleteExposeDirectoryAndChannelFailures() throws Exception {
        MetadataFaults fs = new MetadataFaults();
        Path path = directory.resolve("sync");
        try (FaultChannels channels = new FaultChannels()) {
            NioVfs vfs = vfs(fs, channels);
            NioVfs.File file = opened(vfs, path, CREATE);
            try {
                assertEquals(SQLITE_OK, file.write(ByteBuffer.wrap(new byte[] {8}), 0));
                assertEquals(SQLITE_OK, file.sync(SQLITE_SYNC_NORMAL | SQLITE_SYNC_DATAONLY));
                assertEquals(SQLITE_OK, file.sync(SQLITE_SYNC_FULL));
                channels.fail(FaultChannels.Operation.FORCE, 1);
                assertEquals(SQLITE_IOERR_FSYNC, file.sync(SQLITE_SYNC_NORMAL));
            } finally {
                assertEquals(SQLITE_OK, file.close());
            }
            fs.fail(MetadataFaults.Op.DELETE, 1, new AccessDeniedException(path.toString()));
            assertEquals(SQLITE_IOERR_DELETE, vfs.delete(path.toString(), false));
            assertTrue(Files.exists(path));
            fs.fail(MetadataFaults.Op.SYNC, 1, new AccessDeniedException(directory.toString()));
            assertEquals(SQLITE_IOERR_DIR_FSYNC, vfs.delete(path.toString(), true));
            assertFalse(Files.exists(path), "Delete succeeded even though its durability request failed");
            assertEquals(SQLITE_IOERR_DELETE_NOENT, vfs.delete(path.toString(), false));
            assertEquals(SQLITE_IOERR_DELETE, vfs.delete(directory.toString(), false));
            assertTrue(Files.isDirectory(directory));
        }
    }

    @Test
    void firstJournalSyncUsesOnlyTheNativePlatformDirectoryScope() throws Exception {
        MetadataFaults fs = new MetadataFaults();
        NioVfs vfs = vfs(fs, FileChannel::open);
        NioVfs.File file = opened(vfs, directory.resolve("db-journal"),
                SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_MAIN_JOURNAL);
        try {
            assertEquals(SQLITE_OK, file.write(ByteBuffer.wrap(new byte[] {1}), 0));
            fs.fail(MetadataFaults.Op.SYNC, 1, new AccessDeniedException("directory force"));
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertEquals(SQLITE_OK, file.sync(SQLITE_SYNC_FULL));
            } else {
                assertEquals(SQLITE_IOERR_DIR_FSYNC, file.sync(SQLITE_SYNC_NORMAL));
                assertEquals(SQLITE_OK, file.sync(SQLITE_SYNC_NORMAL));
                fs.fail(MetadataFaults.Op.SYNC, 1, new IOException("must not run again"));
                assertEquals(SQLITE_OK, file.sync(SQLITE_SYNC_NORMAL));
            }
        } finally {
            assertEquals(SQLITE_OK, file.close());
        }
    }

    @Test
    void deleteOnCloseFailureIsVisibleAndCloseIsIdempotent() throws Exception {
        MetadataFaults fs = new MetadataFaults();
        NioVfs vfs = vfs(fs, FileChannel::open);
        NioVfs.File file = opened(vfs, null, TEMP);
        fs.fail(MetadataFaults.Op.DELETE, 1, new AccessDeniedException("close cleanup"));
        assertEquals(SQLITE_IOERR_CLOSE, file.close());
        assertEquals(SQLITE_IOERR_CLOSE, file.close());
        assertTrue(vfs.lastError().contains("close cleanup"));
        assertTrue(Files.exists(fs.temp));
        Files.delete(fs.temp);
        assertFalse(Files.exists(fs.temp));
        assertEquals(SQLITE_IOERR_FSTAT, file.fileSize().code());
    }

    @Test
    void badInputsCannotModifyExistingContentOrAdvertiseOptimisticCapabilities() throws Exception {
        Path path = directory.resolve("boundaries");
        Files.write(path, new byte[] {1, 2});
        NioVfs vfs = new NioVfs();
        for (int flags : new int[] {0, SQLITE_OPEN_MAIN_DB, CREATE | SQLITE_OPEN_READONLY,
                SQLITE_OPEN_READONLY | SQLITE_OPEN_CREATE | SQLITE_OPEN_MAIN_DB,
                RW | SQLITE_OPEN_EXCLUSIVE, RW | SQLITE_OPEN_DELETEONCLOSE,
                CREATE | SQLITE_OPEN_TEMP_DB, CREATE | SQLITE_OPEN_MEMORY, CREATE | 0x40000000}) {
            assertEquals(SQLITE_MISUSE, vfs.open(path.toString(), flags).code());
        }
        assertEquals(SQLITE_MISUSE, vfs.open(null, CREATE).code());
        assertEquals(SQLITE_CANTOPEN, vfs.open("", CREATE).code());
        assertEquals(SQLITE_CANTOPEN_FULLPATH, vfs.fullPathname("bad\u0000path").code());
        assertEquals(SQLITE_MISUSE, vfs.access(path.toString(), -1).code());
        NioVfs.File file = opened(vfs, path, RW);
        try {
            assertEquals(SQLITE_MISUSE, file.read(null, 0));
            assertEquals(SQLITE_MISUSE, file.read(ByteBuffer.allocate(1).asReadOnlyBuffer(), 0));
            assertEquals(SQLITE_MISUSE, file.write(ByteBuffer.allocate(1), -1));
            assertEquals(SQLITE_MISUSE, file.write(ByteBuffer.allocate(1), Long.MAX_VALUE));
            assertEquals(SQLITE_MISUSE, file.truncate(-1));
            assertEquals(SQLITE_MISUSE, file.sync(SQLITE_SYNC_DATAONLY));
            assertEquals(SQLITE_MISUSE, file.sync(SQLITE_SYNC_NORMAL | 0x100));
            assertEquals(SQLITE_MISUSE, file.lock(SQLITE_LOCK_PENDING));
            assertEquals(SQLITE_MISUSE, file.lock(SQLITE_LOCK_EXCLUSIVE));
            assertEquals(SQLITE_MISUSE, file.unlock(SQLITE_LOCK_RESERVED));
            assertEquals(SQLITE_NOTFOUND, file.fileControl(Integer.MAX_VALUE, 0).code());
            assertEquals(new NioVfs.LongResult(SQLITE_OK, 0), file.fileControl(SQLITE_FCNTL_MMAP_SIZE, Long.MAX_VALUE));
            assertEquals(SQLITE_NOTFOUND, file.fileControl(SQLITE_FCNTL_POWERSAFE_OVERWRITE, 1).code());
            assertEquals(0, file.deviceCharacteristics());
            assertEquals(4096, file.sectorSize());
        } finally {
            assertEquals(SQLITE_OK, file.close());
        }
        assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(path));
    }

    @ParameterizedTest(name = "clock and thread-local helpers, virtualThreads={0}")
    @ValueSource(booleans = {false, true})
    void clockRandomnessSleepAndErrorsStayIndependentAcrossThreads(boolean virtual) throws Exception {
        assumeTrue(!virtual || JdkSupport.hasVirtualThreads(), "Virtual threads require JDK 21 or later");
        for (Instant instant : new Instant[] {Instant.EPOCH, Instant.parse("2000-02-29T00:00:00Z"),
                Instant.parse("2024-03-01T00:00:00Z")}) {
            NioVfs vfs = new NioVfs(FileSystemOps.SYSTEM, FileChannel::open,
                    Clock.fixed(instant, ZoneOffset.UTC), new Random(7));
            long expectedDays;
            switch (instant.toString()) {
                case "1970-01-01T00:00:00Z": expectedDays = 2440587; break;
                case "2000-02-29T00:00:00Z": expectedDays = 2451603; break;
                default: expectedDays = 2460370; break;
            }
            assertEquals(expectedDays + 0.5, vfs.currentTimeJulian());
            assertEquals((expectedDays * 2 + 1) * 43_200_000L, vfs.currentTimeMillisJulian());
        }
        NioVfs first = new NioVfs(FileSystemOps.SYSTEM, FileChannel::open,
                Clock.systemUTC(), new Random() {
                    @Override public long nextLong() { return 0x5a5a5a5a5a5a5a5aL; }
                });
        ByteBuffer actual = ByteBuffer.wrap(new byte[20]);
        actual.position(2).limit(19);
        assertEquals(17, first.randomness(actual));
        byte[] expected = new byte[17];
        Arrays.fill(expected, (byte) 0x5a);
        assertArrayEquals(expected, Arrays.copyOfRange(actual.array(), 2, 19));
        assertEquals(19, actual.position());
        assertEquals(0, actual.get(0));
        assertEquals(0, actual.array()[19]);
        assertEquals(0, first.randomness(ByteBuffer.allocate(1).asReadOnlyBuffer()));
        assertEquals(0, first.sleep(0));
        assertEquals(0, first.sleep(-1));
        String originalError = first.lastError();
        ExecutorService executor = virtual
                ? JdkSupport.newVirtualThreadExecutor() : Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                assertEquals("", first.lastError());
                assertTrue(first.sleep(1000) >= 1000);
                Thread.currentThread().interrupt();
                assertTrue(first.sleep(10_000_000) < 10_000_000);
                assertTrue(Thread.interrupted());
                assertTrue(first.lastError().contains("sleep"));
            }).get();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "thread-local helper task did not stop");
        }
        assertEquals(originalError, first.lastError());
        assertNull(first.dlOpen("not-a-library"));
        assertNull(first.dlSym(null, "entrypoint"));
        assertTrue(first.dlError().contains("unsupported"));
        first.dlClose(null);
    }

    @Test
    void deniedJournalCreationDoesNotPretendToBeDiskFull() {
        NioVfs vfs = vfs(FileSystemOps.SYSTEM, (path, options) -> {
            throw new AccessDeniedException(path.toString());
        });
        int expected = System.getProperty("os.name").startsWith("Windows")
                ? SQLITE_CANTOPEN : SQLITE_READONLY_DIRECTORY;
        Path path = directory.resolve("denied-journal");
        assertEquals(expected, vfs.open(path.toString(),
                SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_MAIN_JOURNAL).code());
        assertFalse(Files.exists(path));
    }

    @ParameterizedTest
    @ValueSource(ints = {SQLITE_IOERR_RDLOCK, SQLITE_IOERR_LOCK, SQLITE_IOERR_UNLOCK,
            SQLITE_IOERR_CHECKRESERVEDLOCK})
    void lockFailureResultsPreserveTheBackendsPoisonedState(int resultCode) throws Exception {
        Path path = directory.resolve("lock-fault-" + resultCode);
        try (FaultChannels channels = new FaultChannels()) {
            NioVfs vfs = vfs(FileSystemOps.SYSTEM, channels);
            NioVfs.File file = opened(vfs, path, CREATE);
            NioVfs.File reader = opened(vfs, path, SQLITE_OPEN_READONLY | SQLITE_OPEN_MAIN_DB);
            try {
                if (resultCode == SQLITE_IOERR_LOCK || resultCode == SQLITE_IOERR_UNLOCK) {
                    assertEquals(SQLITE_OK, file.lock(SQLITE_LOCK_SHARED));
                }
                channels.fail(resultCode == SQLITE_IOERR_UNLOCK
                        ? FaultChannels.Operation.UNLOCK : FaultChannels.Operation.LOCK, 1);
                int actual;
                switch (resultCode) {
                    case SQLITE_IOERR_RDLOCK: actual = file.lock(SQLITE_LOCK_SHARED); break;
                    case SQLITE_IOERR_LOCK: actual = file.lock(SQLITE_LOCK_RESERVED); break;
                    case SQLITE_IOERR_UNLOCK: actual = file.unlock(SQLITE_LOCK_NONE); break;
                    default: actual = file.checkReservedLock().code(); break;
                }
                assertEquals(resultCode, actual);
                assertEquals(SQLITE_IOERR_READ, reader.read(ByteBuffer.allocate(1), 0));
                assertEquals(SQLITE_IOERR_WRITE, reader.write(ByteBuffer.allocate(1), 0),
                        "A poisoned state takes precedence over an ordinary read-only result");
                assertEquals(SQLITE_CANTOPEN, vfs.open(path.toString(), RW).code());
            } finally {
                assertEquals(SQLITE_IOERR_CLOSE, reader.close());
                assertEquals(SQLITE_IOERR_CLOSE, file.close());
            }
            NioVfs.File recovered = opened(vfs, path, RW);
            assertEquals(SQLITE_OK, recovered.lock(SQLITE_LOCK_SHARED));
            assertEquals(SQLITE_OK, recovered.close());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeFailureQuarantinesTheIdentityInAnIsolatedJvm(boolean closeFirst) throws Exception {
        try (var proof = new RollbackFileTest.Child(CloseFailurePeer.class,
                directory.resolve("quarantine").toString(), Boolean.toString(closeFirst))) {
            assertTrue(Files.exists(directory.resolve("quarantine")));
        }
    }

    public static final class CloseFailurePeer {
        public static void main(String[] args) throws Exception {
            Path path = Path.of(args[0]);
            try (FaultChannels channels = new FaultChannels()) {
                NioVfs vfs = vfs(FileSystemOps.SYSTEM, channels);
                NioVfs.File file = opened(vfs, path, CREATE);
                assertEquals(SQLITE_OK, file.write(ByteBuffer.wrap(new byte[] {3}), 0));
                channels.closeBeforeFailure(Boolean.parseBoolean(args[1]));
                channels.fail(FaultChannels.Operation.CLOSE, 1);
                assertEquals(SQLITE_IOERR_CLOSE, file.close());
                assertEquals(SQLITE_IOERR_CLOSE, file.close());
                assertEquals(SQLITE_CANTOPEN, vfs.open(path.toString(), RW).code());
                channels.closeDelegates();
                assertEquals(SQLITE_CANTOPEN, vfs.open(path.toString(), RW).code());
                assertArrayEquals(new byte[] {3}, Files.readAllBytes(path));
            }
            System.out.println("READY\tNioVfs-close-quarantine\t" + args[1]);
            System.out.flush();
            System.in.read();
        }
    }

    private static final class MetadataFaults extends FileSystemOps {
        enum Op { ATTR, REAL, ACCESS, TEMP, DELETE, SYNC }
        private final Map<Op, Integer> counts = new EnumMap<>(Op.class);
        private final Map<Op, Integer> at = new EnumMap<>(Op.class);
        private final Map<Op, IOException> errors = new EnumMap<>(Op.class);
        Path temp;

        void fail(Op operation, int nth, IOException failure) {
            at.put(operation, counts.getOrDefault(operation, 0) + nth);
            errors.put(operation, failure);
        }

        private void before(Op operation) throws IOException {
            int count = counts.merge(operation, 1, Integer::sum);
            if (at.getOrDefault(operation, -1) == count) {
                throw errors.remove(operation);
            }
        }

        @Override BasicFileAttributes attributes(Path path, LinkOption... options) throws IOException {
            before(Op.ATTR);
            return super.attributes(path, options);
        }
        @Override Path realPath(Path path) throws IOException { before(Op.REAL); return super.realPath(path); }
        @Override void checkAccess(Path path, AccessMode... modes) throws IOException {
            before(Op.ACCESS);
            super.checkAccess(path, modes);
        }
        @Override Path createTempFile() throws IOException { before(Op.TEMP); return temp = super.createTempFile(); }
        @Override void delete(Path path) throws IOException { before(Op.DELETE); super.delete(path); }
        @Override void syncDirectory(Path path) throws IOException { before(Op.SYNC); super.syncDirectory(path); }
    }
}
