package io.github.clickin.sqlitevfs;

import io.github.clickin.sqlitevfs.RollbackFileTest.Child;
import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.MappedByteBuffer;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static io.github.clickin.sqlitevfs.SqliteCodes.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class ShmFileTest {
    private static final int SHARED = SQLITE_SHM_LOCK | SQLITE_SHM_SHARED;
    private static final int EXCLUSIVE = SQLITE_SHM_LOCK | SQLITE_SHM_EXCLUSIVE;
    private static final int UNLOCK_EXCLUSIVE = SQLITE_SHM_UNLOCK | SQLITE_SHM_EXCLUSIVE;
    @TempDir Path directory;

    @Test
    void qualifiedRuntimeReportsItsActualUnmapAndEndianCapability() throws Exception {
        assertTrue(NioVfs.sharedMemorySupported(), NioVfs.sharedMemoryDiagnostic());
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder());
        try (Child rejected = new Child(UnsupportedRuntime.class, directory.resolve("unsupported.db").toString())) {
            // Child asserts explicit SQLite failure before its handshake.
        }
    }

    @Test
    void unownedUnlockIsANoopAndCannotReleaseAnotherConnectionsWriter() throws Exception {
        Path path = directory.resolve("unowned-unlock.db");
        NioVfs vfs = new NioVfs();
        try (Child nativeDb = initializedWal(path)) {
            NioVfs.File owner = open(vfs, path);
            NioVfs.File observer = open(vfs, path);
            try {
                mapped(vfs, owner, 0, false);
                mapped(vfs, observer, 0, false);
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals(SQLITE_OK, owner.shmLock(0, 1, EXCLUSIVE));
                assertEquals(SQLITE_OK, observer.shmLock(0, 3, UNLOCK_EXCLUSIVE));
                nativeDb.busy("BEGIN IMMEDIATE");
                assertEquals(SQLITE_OK, owner.shmLock(0, 3, UNLOCK_EXCLUSIVE));
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("ROLLBACK");
            } finally {
                assertEquals(SQLITE_OK, observer.close());
                assertEquals(SQLITE_OK, owner.close());
            }
        }
    }

    @Test
    void nativeIndexUpdatesAreVisibleAndWriterLocksExcludeBothWays() throws Exception {
        Path path = directory.resolve("native.db");
        NioVfs vfs = new NioVfs();
        try (Child nativeDb = initializedWal(path)) {
            NioVfs.File file = open(vfs, path);
            try {
                ByteBuffer page = mapped(vfs, file, 0, false);
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertTrue(page.isDirect());
                assertEquals(32768, page.capacity());
                assertEquals(ByteOrder.nativeOrder(), page.order());
                assertEquals(3007000, page.getInt(0));
                int change = page.getInt(8);
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                file.shmBarrier();
                assertEquals(change + 1, page.getInt(8), "native writes must reach the original mapping without remap");
                assertEquals(SQLITE_OK, file.shmLock(0, 1, EXCLUSIVE));
                nativeDb.busy("BEGIN IMMEDIATE");
                assertEquals(SQLITE_OK, file.shmLock(0, 1, UNLOCK_EXCLUSIVE));
                nativeDb.ok("BEGIN IMMEDIATE");
                assertEquals(SQLITE_BUSY, file.shmLock(0, 1, EXCLUSIVE));
                nativeDb.ok("ROLLBACK");
                assertEquals(SQLITE_OK, file.shmLock(0, 1, EXCLUSIVE));
                assertEquals(SQLITE_OK, file.close(), vfs.lastError());
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("ROLLBACK");
                assertEquals("32", nativeDb.ok("SELECT sum(value) FROM sample"));
            } finally {
                assertEquals(SQLITE_OK, file.close(), vfs.lastError());
            }
        }
    }

    @Test
    void localSharedHoldersAndUnrelatedClosesRetainTheNativeLock() throws Exception {
        Path path = directory.resolve("holders.db");
        NioVfs vfs = new NioVfs();
        try (Child nativeDb = initializedWal(path)) {
            NioVfs.File first = open(vfs, path);
            NioVfs.File second = open(vfs, path);
            NioVfs.File observer = open(vfs, path);
            try {
                mapped(vfs, first, 0, false);
                mapped(vfs, second, 0, false);
                mapped(vfs, observer, 0, false);
                // Raw VFS tests have no engine to recover the invalidated header.
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals(SQLITE_OK, first.shmLock(0, 1, SHARED));
                assertEquals(SQLITE_OK, first.shmLock(0, 1, SHARED), "repeat acquisition must not leak a shared-holder count");
                assertEquals(SQLITE_OK, second.shmLock(0, 1, SHARED));
                assertEquals(SQLITE_BUSY, observer.shmLock(0, 1, EXCLUSIVE));
                assertEquals(SQLITE_OK, observer.close());
                assertEquals(SQLITE_OK, first.close());
                nativeDb.busy("BEGIN IMMEDIATE");
                assertEquals(SQLITE_OK, second.shmUnmap(false));
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("ROLLBACK");
            } finally {
                assertEquals(SQLITE_OK, first.close());
                assertEquals(SQLITE_OK, second.close());
                assertEquals(SQLITE_OK, observer.close());
            }
        }
    }

    @Test
    void hardLinkedShmAliasesShareOneChannelAndHolderAggregation() throws Exception {
        Path path = directory.resolve("identity.db");
        Path alias = directory.resolve("identity-alias.db");
        NioVfs vfs = new NioVfs();
        try (Child nativeDb = initializedWal(path)) {
            Files.createLink(alias, path);
            Files.createLink(sidecar(alias), sidecar(path));
            NioVfs.File first = open(vfs, path);
            NioVfs.File second = open(vfs, alias);
            try {
                mapped(vfs, first, 0, false);
                mapped(vfs, second, 0, false);
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals(SQLITE_OK, first.shmLock(0, 1, SHARED));
                assertEquals(SQLITE_OK, second.shmLock(0, 1, SHARED));
                assertEquals(SQLITE_OK, first.close());
                nativeDb.busy("BEGIN IMMEDIATE");
                assertEquals(SQLITE_OK, second.close());
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("ROLLBACK");
            } finally {
                assertEquals(SQLITE_OK, first.close());
                assertEquals(SQLITE_OK, second.close());
            }
        }
    }

    @Test
    void partialMultibyteBusyRollsBackOnlyNewLocks() throws Exception {
        Path path = directory.resolve("partial.db");
        NioVfs vfs = new NioVfs();
        try (Child nativeDb = initializedWal(path)) {
            NioVfs.File first = open(vfs, path);
            NioVfs.File second = open(vfs, path);
            try {
                mapped(vfs, first, 0, false);
                mapped(vfs, second, 0, false);
                assertEquals(SQLITE_OK, first.shmLock(0, 1, EXCLUSIVE));
                try (Child recovery = new Child(LockHolder.class, sidecar(path).toString(), "122")) {
                    assertEquals(SQLITE_BUSY, first.shmLock(0, 3, EXCLUSIVE));
                    assertEquals(SQLITE_OK, second.shmLock(1, 1, EXCLUSIVE));
                    assertEquals(SQLITE_BUSY, second.shmLock(0, 1, EXCLUSIVE));
                    nativeDb.busy("BEGIN IMMEDIATE");
                    assertEquals(SQLITE_OK, second.shmLock(1, 1, UNLOCK_EXCLUSIVE));
                }
                assertEquals(SQLITE_OK, first.shmLock(0, 3, EXCLUSIVE));
                assertEquals(SQLITE_OK, first.shmLock(0, 3, UNLOCK_EXCLUSIVE));
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("ROLLBACK");
            } finally {
                assertEquals(SQLITE_OK, first.close());
                assertEquals(SQLITE_OK, second.close());
            }
        }
    }

    @Test
    void dmsContentionRetriesAndFirstOpenerDiscardsStaleIndex() throws Exception {
        Path path = directory.resolve("deadman.db");
        try (Child initialized = initializedWal(path)) {
            assertEquals("31", initialized.ok("SELECT sum(value) FROM sample"));
        }
        NioVfs vfs = new NioVfs();
        NioVfs.File file = open(vfs, path);
        try {
            try (Child initializer = new Child(LockHolder.class, sidecar(path).toString(), "128")) {
                assertEquals(SQLITE_BUSY, file.shmMap(0, 32768, false).code());
            }
            NioVfs.ShmResult missing = file.shmMap(0, 32768, false);
            assertEquals(SQLITE_OK, missing.code(), vfs.lastError());
            assertNull(missing.region());
            assertEquals(0, Files.size(sidecar(path)));
            ByteBuffer reset = mapped(vfs, file, 0, true);
            assertEquals(0, reset.getInt(0));
            assertEquals(0, reset.getInt(8));
            assertEquals(32768, Files.size(sidecar(path)));
        } finally {
            assertEquals(SQLITE_OK, file.close(), vfs.lastError());
        }
    }

    @Test
    void growthKeepsViewsSharedAndFinalDetachSynchronouslyUnmapsEveryRegion() throws Exception {
        Path path = directory.resolve("growth.db");
        NioVfs vfs = new NioVfs();
        NioVfs.File first = open(vfs, path);
        NioVfs.File second = open(vfs, path);
        long baseline = mappedCount();
        try {
            ByteBuffer original = mapped(vfs, first, 0, true);
            original.putInt(512, 0x12345678);
            ByteBuffer alias = mapped(vfs, second, 0, false);
            assertEquals(0x12345678, alias.getInt(512));
            assertEquals(baseline + 1, mappedCount(), "local aliases reuse one OS mapping");
            NioVfs.ShmResult absent = second.shmMap(2, 32768, false);
            assertEquals(SQLITE_OK, absent.code());
            assertNull(absent.region());
            assertEquals(32768, Files.size(sidecar(path)));
            ByteBuffer grown = mapped(vfs, second, 2, true);
            grown.putLong(4096, 0x123456789abcdef0L);
            assertEquals(3 * 32768, Files.size(sidecar(path)));
            assertEquals(0x12345678, original.getInt(512));
            assertEquals(SQLITE_OK, first.shmUnmap(true));
            assertTrue(Files.exists(sidecar(path)), "nonfinal delete must not unlink sibling mappings");
            alias.putInt(512, 0x76543210);
            assertEquals(0x123456789abcdef0L, grown.getLong(4096));
            assertEquals(baseline + 2, mappedCount());
            assertEquals(SQLITE_OK, second.shmUnmap(true), vfs.lastError());
            assertEquals(baseline, mappedCount(), "unmap must finish before return, without GC");
            assertFalse(Files.exists(sidecar(path)));
            ByteBuffer reopened = mapped(vfs, first, 0, true);
            assertEquals(0, reopened.getInt(512), "new node must not expose the retired mapping");
        } finally {
            assertEquals(SQLITE_OK, first.close(), vfs.lastError());
            assertEquals(SQLITE_OK, second.close(), vfs.lastError());
        }
        assertEquals(baseline, mappedCount());
    }

    @Test
    void requestedDeleteDoesNotUnlinkAnIndexAttachedByNativeSqlite() throws Exception {
        Path path = directory.resolve("live-delete.db");
        NioVfs vfs = new NioVfs();
        try (Child nativeDb = initializedWal(path)) {
            NioVfs.File file = open(vfs, path);
            try {
                mapped(vfs, file, 0, false);
                assertEquals(SQLITE_OK, file.shmUnmap(true), vfs.lastError());
                assertTrue(Files.exists(sidecar(path)));
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                assertEquals("32", nativeDb.ok("SELECT sum(value) FROM sample"));
            } finally {
                assertEquals(SQLITE_OK, file.close());
            }
        }
    }

    @Test
    void invalidRangesFlagsAndUnownedUnlockCannotAlterHeldLocks() throws Exception {
        Path path = directory.resolve("flags.db");
        NioVfs vfs = new NioVfs();
        try (Child nativeDb = initializedWal(path)) {
            NioVfs.File file = open(vfs, path);
            try {
                assertEquals(SQLITE_MISUSE, file.shmMap(-1, 32768, true).code());
                assertEquals(SQLITE_MISUSE, file.shmMap(0, 4096, true).code());
                mapped(vfs, file, 0, false);
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals(SQLITE_OK, file.shmLock(0, 1, EXCLUSIVE));
                for (int flags : new int[] {0, SQLITE_SHM_LOCK, EXCLUSIVE | SQLITE_SHM_SHARED,
                        EXCLUSIVE | SQLITE_SHM_UNLOCK, EXCLUSIVE | 0x100}) {
                    assertEquals(SQLITE_MISUSE, file.shmLock(0, 1, flags));
                }
                assertEquals(SQLITE_MISUSE, file.shmLock(-1, 1, EXCLUSIVE));
                assertEquals(SQLITE_MISUSE, file.shmLock(7, 2, EXCLUSIVE));
                assertEquals(SQLITE_MISUSE, file.shmLock(0, Integer.MAX_VALUE, EXCLUSIVE));
                assertEquals(SQLITE_MISUSE, file.shmLock(0, 0, EXCLUSIVE));
                assertEquals(SQLITE_MISUSE, file.shmLock(1, 2, SHARED));
                assertEquals(SQLITE_MISUSE, file.shmLock(0, 1, SQLITE_SHM_UNLOCK | SQLITE_SHM_SHARED));
                nativeDb.busy("BEGIN IMMEDIATE");
            } finally {
                assertEquals(SQLITE_OK, file.close());
            }
        }
    }

    @Test
    void lockIoErrorPoisonsSiblingsAndRetainsPreexistingOwnershipUntilClose() throws Exception {
        Path path = directory.resolve("lock-error.db");
        try (Child nativeDb = initializedWal(path); FaultChannels faults = new FaultChannels()) {
            NioVfs vfs = new NioVfs(FileSystemOps.SYSTEM, faults, Clock.systemUTC(), new Random(1));
            NioVfs.File first = open(vfs, path);
            NioVfs.File second = open(vfs, path);
            try {
                mapped(vfs, first, 0, false);
                mapped(vfs, second, 0, false);
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals(SQLITE_OK, first.shmLock(0, 1, EXCLUSIVE));
                faults.fail(FaultChannels.Operation.LOCK, 2);
                assertEquals(SQLITE_IOERR_SHMLOCK, first.shmLock(0, 3, EXCLUSIVE));
                assertEquals(SQLITE_IOERR_SHMLOCK, second.shmLock(1, 1, EXCLUSIVE));
                assertEquals(SQLITE_IOERR_SHMLOCK, second.shmMap(0, 32768, false).code());
                nativeDb.busy("BEGIN IMMEDIATE");
                assertEquals(SQLITE_OK, second.close());
                nativeDb.busy("BEGIN IMMEDIATE");
                assertEquals(SQLITE_OK, first.close());
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("ROLLBACK");
            } finally {
                assertEquals(SQLITE_OK, second.close());
                assertEquals(SQLITE_OK, first.close());
            }
        }
    }

    @Test
    void failedUnlockKeepsItsLockAndMappingUntilCleanupIsRetried() throws Exception {
        Path path = directory.resolve("unlock-error.db");
        try (Child nativeDb = initializedWal(path); FaultChannels faults = new FaultChannels()) {
            NioVfs vfs = new NioVfs(FileSystemOps.SYSTEM, faults, Clock.systemUTC(), new Random(1));
            NioVfs.File file = open(vfs, path);
            long baseline = mappedCount();
            try {
                mapped(vfs, file, 0, false);
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals(SQLITE_OK, file.shmLock(0, 1, EXCLUSIVE));
                faults.fail(FaultChannels.Operation.UNLOCK, 1);
                assertEquals(SQLITE_IOERR_CLOSE, file.close());
                assertEquals(baseline + 1, mappedCount());
                nativeDb.busy("BEGIN IMMEDIATE");
                assertEquals(SQLITE_OK, file.close(), vfs.lastError());
                assertEquals(baseline, mappedCount());
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("ROLLBACK");
            } finally {
                assertEquals(SQLITE_OK, file.close());
            }
        }
    }

    @Test
    void readonlyMainDatabaseCanUseWritableSharedMemory() throws Exception {
        Path path = directory.resolve("readonly-main.db");
        NioVfs vfs = new NioVfs();
        try (Child nativeDb = initializedWal(path)) {
            NioVfs.OpenResult opened = vfs.open(path.toString(), SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_READONLY);
            assertEquals(SQLITE_OK, opened.code(), vfs.lastError());
            NioVfs.File file = opened.file();
            try {
                assertEquals(SQLITE_OK, file.lock(SQLITE_LOCK_SHARED));
                ByteBuffer page = mapped(vfs, file, 0, false);
                assertFalse(page.isReadOnly());
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                int change = page.getInt(8);
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                file.shmBarrier();
                assertEquals(change + 1, page.getInt(8));
                assertEquals(SQLITE_READONLY, file.write(ByteBuffer.wrap(new byte[] {1}), 0));
            } finally {
                assertEquals(SQLITE_OK, file.close());
            }
        }
    }

    @Test
    void readonlyShmFallbackExplicitlyRejectsUnprovableInitialization() throws Exception {
        Path path = directory.resolve("readonly.db");
        try (Child nativeDb = initializedWal(path)) {
            Path canonicalShm = sidecar(path.toRealPath());
            RollbackFile.ChannelOpener opener = (name, options) -> {
                if (name.equals(canonicalShm) && Arrays.asList(options).contains(StandardOpenOption.WRITE)) {
                    throw new IOException("Test denies SHM writes; descriptor still real");
                }
                return FileChannel.open(name, options);
            };
            NioVfs vfs = new NioVfs(FileSystemOps.SYSTEM, opener, Clock.systemUTC(), new Random(1));
            NioVfs.File file = open(vfs, path);
            try {
                NioVfs.ShmResult readonly = file.shmMap(0, 32768, false);
                assertEquals(SQLITE_READONLY_CANTINIT, readonly.code());
                assertNull(readonly.region());
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
            } finally {
                assertEquals(SQLITE_OK, file.close());
            }
        }
    }

    @Test
    void nativeActiveTransactionsBlockOnlyTheFirstJvmAttachment() throws Exception {
        Path path = directory.resolve("attach-busy.db");
        NioVfs vfs = new NioVfs();
        try (Child nativeDb = initializedWal(path)) {
            NioVfs.File first = open(vfs, path);
            NioVfs.File second = open(vfs, path);
            try {
                nativeDb.ok("BEGIN");
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals(SQLITE_BUSY, first.shmMap(0, 32768, false).code());
                nativeDb.ok("ROLLBACK");
                ByteBuffer firstView = mapped(vfs, first, 0, false);
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                nativeDb.ok("BEGIN");
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                ByteBuffer secondView = mapped(vfs, second, 0, false);
                assertEquals(firstView.getInt(16), secondView.getInt(16));
                nativeDb.ok("ROLLBACK");
                nativeDb.ok("BEGIN IMMEDIATE");
                assertEquals(SQLITE_BUSY, second.shmLock(0, 1, EXCLUSIVE));
                nativeDb.ok("ROLLBACK");
            } finally {
                assertEquals(SQLITE_OK, first.close());
                assertEquals(SQLITE_OK, second.close());
            }
        }
    }

    enum CrashStage { BEFORE_ZERO, MID_ZERO, AFTER_ZERO_BEFORE_DMS, AFTER_DMS_BEFORE_RELEASE }

    @ParameterizedTest
    @EnumSource(CrashStage.class)
    void nativeRecoversAfterEveryGuardedInitializationCrashStage(CrashStage stage) throws Exception {
        Path path = directory.resolve("crash-" + stage + ".db");
        try (Child nativeDb = initializedWal(path)) {
            try (Child initializer = new Child(CrashingInitializer.class, path.toString(), stage.name())) {
                // Production initialization is paused with all8 locks held.
                // The original native connection retains its actual live mapping.
                initializer.close();
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                assertEquals("32", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertEquals("ok", nativeDb.ok("PRAGMA integrity_check"));
            }
        }
        try (Child reopened = Child.nativeDb(path, false)) {
            assertEquals("32", reopened.ok("SELECT sum(value) FROM sample"));
            assertEquals("ok", reopened.ok("PRAGMA integrity_check"));
        }
    }

    @Test
    void failedExclusiveInitializerCannotMakeStaleIndexHideACommittedTransaction() throws Exception {
        Path path = directory.resolve("stale-index.db");
        byte[] stale;
        try (Child nativeDb = initializedWal(path)) {
            stale = Files.readAllBytes(sidecar(path));
            nativeDb.ok("UPDATE sample SET value=value+10 WHERE id=1");
            assertEquals("41", nativeDb.ok("SELECT sum(value) FROM sample"));
        }
        // A prior crash can leave a checksum-valid older SHM image beside a newer WAL.
        Files.write(sidecar(path), stale);
        Path canonicalShm = sidecar(path.toRealPath());
        try (Child initializer = new Child(LockHolder.class, canonicalShm.toString(), "128")) {
            RollbackFile.ChannelOpener opener = (name, options) -> {
                FileChannel channel = FileChannel.open(name, options);
                return name.equals(canonicalShm)
                        ? new CrashGateChannel(channel, initializer::close) : channel;
            };
            NioVfs vfs = new NioVfs(FileSystemOps.SYSTEM, opener, Clock.systemUTC(), new Random(1));
            NioVfs.File file = open(vfs, path);
            try {
                // The holder dies after the real exclusive probe fails, before truncation.
                mapped(vfs, file, 0, false);
                try (Child observer = Child.nativeWalDb(path, false)) {
                    assertEquals("41", observer.ok("SELECT sum(value) FROM sample"),
                            "a stale index must not hide the latest committed WAL frame");
                    observer.ok("UPDATE sample SET value=value+1 WHERE id=1");
                    assertEquals("42", observer.ok("SELECT sum(value) FROM sample"));
                    assertEquals("ok", observer.ok("PRAGMA integrity_check"));
                }
            } finally {
                assertEquals(SQLITE_OK, file.close(), vfs.lastError());
            }
        }
    }

    public static final class CrashingInitializer {
        public static void main(String[] args) throws Exception {
            Path database = Path.of(args[0]);
            CrashStage stage = CrashStage.valueOf(args[1]);
            Path canonicalShm = sidecar(database.toRealPath());
            RollbackFile.ChannelOpener opener = (path, options) -> {
                FileChannel channel = FileChannel.open(path, options);
                return path.equals(canonicalShm) ? new CrashGateChannel(channel, stage) : channel;
            };
            NioVfs vfs = new NioVfs(FileSystemOps.SYSTEM, opener, Clock.systemUTC(), new Random(1));
            NioVfs.File file = open(vfs, database);
            NioVfs.ShmResult result = file.shmMap(0, 32768, false);
            throw new AssertionError("Initialization passed crash gate: " + result.code() + " " + vfs.lastError());
        }
    }

    /** Pauses real production syscalls, never substitutes a lock or mapping result. */
    private static final class CrashGateChannel extends MappingTestChannel {
        private final CrashStage stage;
        private final Runnable afterFailedExclusive;

        CrashGateChannel(FileChannel delegate, CrashStage stage) {
            super(delegate);
            this.stage = stage;
            this.afterFailedExclusive = null;
        }

        CrashGateChannel(FileChannel delegate, Runnable afterFailedExclusive) {
            super(delegate);
            this.stage = null;
            this.afterFailedExclusive = afterFailedExclusive;
        }

        private void pause(CrashStage point) throws IOException {
            if (stage != point) return;
            System.out.println("READY\tSHM-initialization-crash\tstage=" + stage);
            System.out.flush();
            System.in.read();
            throw new IOException("Parent must terminate process at crash gate");
        }

        @Override public int write(ByteBuffer source, long offset) throws IOException {
            if (offset == 48) pause(CrashStage.BEFORE_ZERO);
            int count = delegate.write(source, offset);
            if (offset == 48 && !source.hasRemaining()) pause(CrashStage.MID_ZERO);
            return count;
        }

        @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            if (position == 128 && shared) pause(CrashStage.AFTER_ZERO_BEFORE_DMS);
            FileLock result = delegate.tryLock(position, size, shared);
            if (position == 128 && !shared && result == null && afterFailedExclusive != null) {
                afterFailedExclusive.run();
            }
            if (position == 128 && shared && result != null) pause(CrashStage.AFTER_DMS_BEFORE_RELEASE);
            return result;
        }

        @Override public int read(ByteBuffer target) throws IOException { return delegate.read(target); }
        @Override public int read(ByteBuffer target, long offset) throws IOException { return delegate.read(target, offset); }
        @Override public long read(ByteBuffer[] targets, int offset, int length) throws IOException { return delegate.read(targets, offset, length); }
        @Override public int write(ByteBuffer source) throws IOException { return delegate.write(source); }
        @Override public long write(ByteBuffer[] sources, int offset, int length) throws IOException { return delegate.write(sources, offset, length); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
        @Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
        @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException { return delegate.transferTo(position, count, target); }
        @Override public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException { return delegate.transferFrom(source, position, count); }
        @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
        @Override public FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
        @Override protected void implCloseChannel() throws IOException { delegate.close(); }
    }

    private static Child initializedWal(Path path) throws Exception {
        Child child = Child.nativeDb(path, true);
        try {
            assertEquals("wal", child.ok("PRAGMA journal_mode=WAL"));
            child.ok("PRAGMA wal_autocheckpoint=0");
            child.ok("UPDATE sample SET value=value+1 WHERE id=1");
            return child;
        } catch (Exception | Error failure) {
            child.close();
            throw failure;
        }
    }

    private static NioVfs.File open(NioVfs vfs, Path path) {
        NioVfs.OpenResult result = vfs.open(path.toString(), SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE);
        assertEquals(SQLITE_OK, result.code(), vfs.lastError());
        assertEquals(SQLITE_OK, result.file().lock(SQLITE_LOCK_SHARED), vfs.lastError());
        return result.file();
    }

    private static ByteBuffer mapped(NioVfs vfs, NioVfs.File file, int page, boolean extend) {
        NioVfs.ShmResult result = file.shmMap(page, 32768, extend);
        assertEquals(SQLITE_OK, result.code(), vfs.lastError());
        assertNotNull(result.region());
        return result.region();
    }

    private static Path sidecar(Path path) { return path.resolveSibling(path.getFileName() + "-shm"); }

    private static long mappedCount() {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("mapped")).mapToLong(BufferPoolMXBean::getCount).sum();
    }

    public static final class LockHolder {
        public static void main(String[] args) throws Exception {
            try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock(Long.parseLong(args[1]), 1, false)) {
                assertNotNull(lock);
                System.out.println("READY\tSHM-exclusive\tbyte=" + args[1]);
                System.out.flush();
                System.in.read();
            }
        }
    }

    public static final class UnsupportedRuntime {
        public static void main(String[] args) {
            System.setProperty("os.name", "Unqualified test platform");
            assertFalse(NioVfs.sharedMemorySupported());
            NioVfs vfs = new NioVfs();
            NioVfs.File file = open(vfs, Path.of(args[0]));
            try {
                assertEquals(SQLITE_IOERR_SHMMAP, file.shmMap(0, 32768, true).code());
            } finally {
                assertEquals(SQLITE_OK, file.close());
            }
            System.out.println("READY\tSHM-unavailable\texplicit-IOERR_SHMMAP");
        }
    }
}
