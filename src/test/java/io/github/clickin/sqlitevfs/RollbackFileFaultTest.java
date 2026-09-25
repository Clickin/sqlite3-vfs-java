package io.github.clickin.sqlitevfs;

import io.github.clickin.sqlitevfs.RollbackFileTest.Child;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static io.github.clickin.sqlitevfs.FaultChannels.Operation.*;
import static io.github.clickin.sqlitevfs.RollbackFile.Level.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 90, unit = TimeUnit.SECONDS)
class RollbackFileFaultTest {
    @TempDir
    Path directory;

    @Test
    void partialIoAdvancesOnlyTheRequestedBufferWindowAndZeroFillsOnlyEof() throws Exception {
        Path path = directory.resolve("partial");
        Files.write(path, new byte[] {8, 8, 8, 8, 8, 8, 8, 8, 8, 8});
        try (FaultChannels faults = new FaultChannels();
             RollbackFile file = RollbackFile.open(path, false, faults)) {
            faults.partial(2, 3);
            ByteBuffer source = ByteBuffer.wrap(new byte[] {99, 1, 2, 3, 4, 5, 6, 99});
            source.position(1).limit(7);
            file.write(source, 2);
            assertEquals(7, source.position());
            assertEquals(7, source.limit());

            ByteBuffer destination = ByteBuffer.wrap(new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
            destination.position(2).limit(9);
            assertEquals(RollbackFile.OK, file.read(destination, 3));
            assertEquals(9, destination.position());
            assertEquals(9, destination.limit());
            assertArrayEquals(new byte[] {9, 9, 2, 3, 4, 5, 6, 8, 8, 9}, destination.array());

            ByteBuffer eof = ByteBuffer.wrap(new byte[] {9, 9, 9, 9, 9, 9, 9, 9});
            eof.position(1).limit(7);
            assertEquals(RollbackFile.SHORT_READ, file.read(eof, 8));
            assertEquals(7, eof.position());
            assertEquals(7, eof.limit());
            assertArrayEquals(new byte[] {9, 8, 8, 0, 0, 0, 0, 9}, eof.array());
        }
        assertArrayEquals(new byte[] {8, 8, 1, 2, 3, 4, 5, 6, 8, 8}, Files.readAllBytes(path));
    }

    @Test
    void failedSecondWriteLeavesTheRealPrefixAndUnchangedSuffixWithoutDroppingReserved() throws Exception {
        Path path = directory.resolve("partial-write.db");
        try (Child nativeDb = Child.nativeDb(path, true);
             FaultChannels faults = new FaultChannels()) {
            byte[] expected = Files.readAllBytes(path);
            // SQLite's application-owned user_version field can change without corrupting the database.
            expected[60] = 0x12;
            expected[61] = 0x34;
            try (RollbackFile file = RollbackFile.open(path, false, faults)) {
                assertTrue(file.lock(SHARED));
                assertTrue(file.lock(RESERVED));
                faults.partial(2, 2);
                IOException failure = faults.fail(WRITE, 2);
                ByteBuffer source = ByteBuffer.wrap(new byte[] {99, 0x12, 0x34, 0x56, 0x78, 99});
                source.position(1).limit(5);
                assertSame(failure, assertThrows(IOException.class, () -> file.write(source, 60)));
                assertEquals(3, source.position());
                assertEquals(5, source.limit());
                ByteBuffer actual = ByteBuffer.allocate(4);
                assertEquals(RollbackFile.OK, file.read(actual, 60));
                assertArrayEquals(new byte[] {0x12, 0x34, 0, 0}, actual.array());
                nativeDb.busy("BEGIN IMMEDIATE");
                assertEquals(RESERVED, file.level());
                file.sync(true);
            }
            // Opening and closing an unrelated descriptor while locks are held would erase POSIX locks.
            assertArrayEquals(expected, Files.readAllBytes(path));
            // Raw header writes do not update SQLite's pager change counter.
            nativeDb.close();
            try (Child observer = Child.nativeDb(path, false)) {
                assertEquals("305397760", observer.ok("PRAGMA user_version"));
                assertEquals("30", observer.ok("SELECT sum(value) FROM sample"));
                observer.ok("BEGIN IMMEDIATE");
                observer.ok("ROLLBACK");
            }
        }
    }

    @Test
    void readExceptionIsNotShortReadAndDoesNotZeroFillUnreadBytesOrReleaseLocks() throws Exception {
        Path path = directory.resolve("read-failure.db");
        try (Child nativeDb = Child.nativeDb(path, true);
             FaultChannels faults = new FaultChannels();
             RollbackFile file = RollbackFile.open(path, false, faults)) {
            assertTrue(file.lock(SHARED));
            assertTrue(file.lock(RESERVED));
            faults.partial(2, 2);
            IOException failure = faults.fail(READ, 2);
            ByteBuffer destination = ByteBuffer.wrap(new byte[] {9, 9, 9, 9, 9, 9, 9, 9});
            destination.position(1).limit(7);
            assertSame(failure, assertThrows(IOException.class, () -> file.read(destination, 0)));
            assertEquals(3, destination.position());
            assertEquals(7, destination.limit());
            assertArrayEquals(new byte[] {9, 'S', 'Q', 9, 9, 9, 9, 9}, destination.array());
            nativeDb.busy("BEGIN IMMEDIATE");
            assertEquals(RollbackFile.OK, file.read(destination, 2));
            assertEquals(7, destination.position());
            assertArrayEquals(new byte[] {9, 'S', 'Q', 'L', 'i', 't', 'e', 9}, destination.array());
            file.unlock(NONE);
            assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = FaultChannels.Operation.class, names = {"READ", "WRITE"})
    void zeroProgressFailsBeforeTheSafetyFuseWithoutChangingBuffersOrDisk(FaultChannels.Operation operation)
            throws Exception {
        Path path = directory.resolve("zero-" + operation);
        Files.write(path, new byte[] {1, 2, 3, 4});
        try (FaultChannels faults = new FaultChannels();
             RollbackFile file = RollbackFile.open(path, false, faults)) {
            faults.zeroProgress(operation, true);
            // Bounds even a buggy spin without relying on sleeps or leaking an interrupted I/O thread.
            IOException fuse = faults.fail(operation, 32);
            ByteBuffer buffer = ByteBuffer.wrap(new byte[] {9, 7, 8, 9});
            buffer.position(1).limit(3);
            IOException actual = assertThrows(IOException.class, () -> {
                if (operation == READ) {
                    file.read(buffer, 1);
                } else {
                    file.write(buffer, 1);
                }
            });
            assertNotSame(fuse, actual, "Zero progress must fail before repeatedly retrying I/O");
            assertEquals(1, buffer.position());
            assertEquals(3, buffer.limit());
            assertArrayEquals(new byte[] {9, 7, 8, 9}, buffer.array());
            faults.zeroProgress(operation, false);
            ByteBuffer unchanged = ByteBuffer.allocate(4);
            assertEquals(RollbackFile.OK, file.read(unchanged, 0));
            assertArrayEquals(new byte[] {1, 2, 3, 4}, unchanged.array());
            if (operation == READ) {
                assertEquals(RollbackFile.OK, file.read(buffer, 1));
                assertArrayEquals(new byte[] {9, 2, 3, 9}, buffer.array());
            } else {
                file.write(buffer, 1);
            }
            assertEquals(3, buffer.position());
        }
        assertArrayEquals(operation == READ ? new byte[] {1, 2, 3, 4} : new byte[] {1, 7, 8, 4},
                Files.readAllBytes(path));
    }

    @Test
    void forceFailureKeepsExclusiveAgainstNativeReadersAndWritersAndCanBeRetried() throws Exception {
        Path path = directory.resolve("force.db");
        try (Child nativeDb = Child.nativeDb(path, true);
             FaultChannels faults = new FaultChannels();
             RollbackFile file = RollbackFile.open(path, false, faults)) {
            assertTrue(file.lock(SHARED));
            assertTrue(file.lock(EXCLUSIVE));
            file.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 42}), 60);
            IOException failure = faults.fail(FORCE, 1);
            assertSame(failure, assertThrows(IOException.class, () -> file.sync(true)));
            nativeDb.busy("SELECT sum(value) FROM sample");
            nativeDb.busy("BEGIN IMMEDIATE");
            file.sync(true);
            assertEquals(EXCLUSIVE, file.level());
            nativeDb.busy("SELECT sum(value) FROM sample");
            file.unlock(NONE);
            nativeDb.close();
            try (Child observer = Child.nativeDb(path, false)) {
                assertEquals("42", observer.ok("PRAGMA user_version"));
                observer.ok("BEGIN IMMEDIATE");
                observer.ok("UPDATE sample SET value=value+1 WHERE id=1");
                observer.ok("COMMIT");
                assertEquals("31", observer.ok("SELECT sum(value) FROM sample"));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = FaultChannels.Operation.class, names = {"SIZE", "TRUNCATE"})
    void sizeAndTruncateFailuresPreserveDataAndReservedLock(FaultChannels.Operation operation) throws Exception {
        Path path = directory.resolve(operation + ".db");
        try (Child nativeDb = Child.nativeDb(path, true);
             FaultChannels faults = new FaultChannels()) {
            byte[] original = Files.readAllBytes(path);
            try (RollbackFile file = RollbackFile.open(path, false, faults)) {
                assertTrue(file.lock(SHARED));
                assertTrue(file.lock(RESERVED));
                IOException failure = faults.fail(operation, 1);
                assertSame(failure, assertThrows(IOException.class, () -> {
                    if (operation == SIZE) {
                        file.size();
                    } else {
                        file.truncate(original.length - 1);
                    }
                }));
                nativeDb.busy("BEGIN IMMEDIATE");
                assertEquals(original.length, file.size());
                file.truncate(original.length);
                assertEquals(original.length, file.size());
                nativeDb.busy("BEGIN IMMEDIATE");
            }
            assertArrayEquals(original, Files.readAllBytes(path));
            nativeDb.ok("BEGIN IMMEDIATE");
            nativeDb.ok("ROLLBACK");
            assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initialOpenFailureDoesNotPublishAStateOrDamageAnExistingFile(boolean existing) throws Exception {
        Path path = directory.resolve("open-" + existing);
        if (existing) {
            Files.write(path, new byte[] {1, 2, 3});
        }
        try (FaultChannels faults = new FaultChannels()) {
            IOException failure = faults.fail(OPEN, 1);
            assertSame(failure, assertThrows(IOException.class, () -> {
                try (RollbackFile unexpected = RollbackFile.open(path, false, faults)) {
                    fail("Initial channel open failure was ignored");
                }
            }));
            assertEquals(existing, Files.exists(path));
            if (existing) {
                assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(path));
            }
            try (RollbackFile file = RollbackFile.open(path, false, faults)) {
                assertTrue(file.lock(SHARED));
                assertTrue(file.lock(EXCLUSIVE));
                file.write(ByteBuffer.wrap(new byte[] {4, 5, 6}), 0);
            }
        }
        assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(path));
    }

    @Test
    void failedWritablePromotionLeavesTheReadOnlyReaderHealthyAndItsNativeLockIntact() throws Exception {
        Path path = directory.resolve("promotion.db");
        try (Child nativeDb = Child.nativeDb(path, true);
             FaultChannels faults = new FaultChannels();
             RollbackFile reader = RollbackFile.open(path, true, faults)) {
            assertTrue(reader.lock(SHARED));
            IOException failure = faults.fail(OPEN, 1);
            assertSame(failure, assertThrows(IOException.class, () -> {
                try (RollbackFile unexpected = RollbackFile.open(path, false)) {
                    fail("Writable promotion failure was ignored");
                }
            }));
            ByteBuffer header = ByteBuffer.allocate(6);
            assertEquals(RollbackFile.OK, reader.read(header, 0));
            assertArrayEquals(new byte[] {'S', 'Q', 'L', 'i', 't', 'e'}, header.array());
            nativeDb.ok("BEGIN IMMEDIATE");
            nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
            nativeDb.busy("COMMIT");
            nativeDb.ok("ROLLBACK");
            try (RollbackFile sibling = RollbackFile.open(path, true)) {
                assertTrue(sibling.lock(SHARED));
            }
            try (RollbackFile writer = RollbackFile.open(path, false)) {
                assertTrue(writer.lock(SHARED));
                assertTrue(writer.lock(RESERVED));
                nativeDb.busy("BEGIN IMMEDIATE");
                assertThrows(IOException.class, () -> reader.write(ByteBuffer.wrap(new byte[] {1}), 60));
            }
            assertEquals(SHARED, reader.level());
            reader.unlock(NONE);
            nativeDb.ok("BEGIN IMMEDIATE");
            nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
            nativeDb.ok("COMMIT");
            assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
        }
    }

    enum Transition {
        JOIN_SHARED, RESERVED_LOCK, PROBE_LOCK, PROBE_RELEASE,
        UPGRADE_LOCK, UPGRADE_RELEASE, DOWNGRADE_LOCK, DOWNGRADE_RELEASE,
        UNLOCK_SHARED, UNLOCK_RESERVED, UNLOCK_PENDING
    }

    @ParameterizedTest
    @EnumSource(Transition.class)
    void lockFailuresPoisonEveryHandleAndKeepRemainingOsLocksUntilFinalClose(Transition transition)
            throws Exception {
        Path path = directory.resolve(transition + ".db");
        try (Child nativeDb = Child.nativeDb(path, true);
             FaultChannels faults = new FaultChannels();
             RollbackFile owner = RollbackFile.open(path, false, faults);
             RollbackFile peer = RollbackFile.open(path, false)) {
            assertTrue(owner.lock(SHARED));
            if (transition == Transition.DOWNGRADE_LOCK || transition == Transition.DOWNGRADE_RELEASE
                    || transition == Transition.UNLOCK_PENDING) {
                assertTrue(owner.lock(EXCLUSIVE));
            } else if (transition == Transition.UNLOCK_RESERVED) {
                assertTrue(owner.lock(RESERVED));
            }
            FaultChannels.Operation operation = switch (transition) {
                case JOIN_SHARED, RESERVED_LOCK, PROBE_LOCK, UPGRADE_LOCK, DOWNGRADE_LOCK -> LOCK;
                default -> UNLOCK;
            };
            int nth = transition == Transition.UPGRADE_LOCK || transition == Transition.UNLOCK_RESERVED
                    || transition == Transition.UNLOCK_PENDING ? 2 : 1;
            IOException failure = faults.fail(operation, nth);
            assertSame(failure, assertThrows(IOException.class, () -> {
                switch (transition) {
                    case JOIN_SHARED -> peer.lock(SHARED);
                    case RESERVED_LOCK -> owner.lock(RESERVED);
                    case PROBE_LOCK, PROBE_RELEASE -> peer.checkReservedLock();
                    case UPGRADE_LOCK, UPGRADE_RELEASE -> owner.lock(EXCLUSIVE);
                    case DOWNGRADE_LOCK, DOWNGRADE_RELEASE -> owner.unlock(SHARED);
                    case UNLOCK_SHARED, UNLOCK_RESERVED, UNLOCK_PENDING -> owner.unlock(NONE);
                }
            }));
            assertPoisoned(path, owner, peer);
            assertNativeStillBlocked(nativeDb, transition);
            assertThrows(IOException.class, owner::close);
            assertPoisoned(path, peer);
            assertNativeStillBlocked(nativeDb, transition);
            assertThrows(IOException.class, peer::close);
            nativeDb.ok("BEGIN IMMEDIATE");
            nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
            nativeDb.ok("COMMIT");
            assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
            try (RollbackFile reopened = RollbackFile.open(path, false)) {
                assertTrue(reopened.lock(SHARED));
                assertTrue(reopened.lock(EXCLUSIVE));
                nativeDb.busy("SELECT sum(value) FROM sample");
            }
        }
    }

    private static void assertNativeStillBlocked(Child nativeDb, Transition transition) throws Exception {
        switch (transition) {
            case JOIN_SHARED, RESERVED_LOCK, PROBE_LOCK, UNLOCK_SHARED -> {
                assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                nativeDb.busy("COMMIT");
                nativeDb.ok("ROLLBACK");
            }
            case PROBE_RELEASE, UNLOCK_RESERVED -> {
                assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
                nativeDb.busy("BEGIN IMMEDIATE");
            }
            default -> nativeDb.busy("SELECT sum(value) FROM sample");
        }
    }

    @Test
    void sharedRangeFailureRetainsPrimaryWhenPendingCleanupAlsoFails() throws Exception {
        Path path = directory.resolve("double-failure.db");
        try (Child nativeDb = Child.nativeDb(path, true);
             FaultChannels faults = new FaultChannels();
             RollbackFile owner = RollbackFile.open(path, false, faults);
             RollbackFile peer = RollbackFile.open(path, false)) {
            IOException acquisition = faults.fail(LOCK, 2);
            IOException cleanup = faults.fail(UNLOCK, 1);
            IOException actual = assertThrows(IOException.class, () -> owner.lock(SHARED));
            assertSame(acquisition, actual);
            assertArrayEquals(new Throwable[] {cleanup}, actual.getSuppressed());
            assertPoisoned(path, owner, peer);
            nativeDb.busy("BEGIN EXCLUSIVE");
            assertThrows(IOException.class, owner::close);
            nativeDb.busy("BEGIN EXCLUSIVE");
            assertThrows(IOException.class, peer::close);
            nativeDb.ok("BEGIN EXCLUSIVE");
            nativeDb.ok("ROLLBACK");
            assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
        }
    }

    private static void assertPoisoned(Path path, RollbackFile... handles) {
        for (RollbackFile file : handles) {
            assertThrows(IOException.class, () -> file.read(ByteBuffer.allocate(1), 0));
            assertThrows(IOException.class, () -> file.write(ByteBuffer.wrap(new byte[] {1}), 0));
            assertThrows(IOException.class, file::size);
            assertThrows(IOException.class, () -> file.truncate(0));
            assertThrows(IOException.class, () -> file.sync(true));
            assertThrows(IOException.class, () -> file.lock(SHARED));
            assertThrows(IOException.class, file::checkReservedLock);
            assertThrows(IOException.class, () -> file.unlock(NONE));
        }
        assertThrows(IOException.class, () -> {
            try (RollbackFile ignored = RollbackFile.open(path, false)) {
                fail("Poisoned state was reopened");
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambiguousDescriptorCloseIsQuarantinedEvenIfTheUnderlyingCloseSucceeded(boolean closeFirst)
            throws Exception {
        Path path = directory.resolve("close-" + closeFirst + ".db");
        // The private scenario asserts its evidence before the bounded Child READY handshake.
        try (Child proof = new Child(CloseFailurePeer.class, path.toString(), Boolean.toString(closeFirst))) {
            try (Child nativeDb = Child.nativeDb(path, false)) {
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
            }
        }
    }

    public static final class CloseFailurePeer {
        public static void main(String[] args) throws Exception {
            PrintStream proof = System.out;
            // Nested native-child diagnostics must not masquerade as this child's READY handshake.
            System.setOut(System.err);
            Path path = Path.of(args[0]);
            boolean closeFirst = Boolean.parseBoolean(args[1]);
            try (Child nativeDb = Child.nativeDb(path, true);
                 FaultChannels faults = new FaultChannels();
                 RollbackFile reader = RollbackFile.open(path, true, faults);
                 RollbackFile writer = RollbackFile.open(path, false)) {
                assertTrue(reader.lock(SHARED));
                assertTrue(writer.lock(SHARED));
                assertTrue(writer.lock(RESERVED));
                reader.close();
                nativeDb.busy("BEGIN IMMEDIATE");
                faults.closeBeforeFailure(closeFirst);
                IOException unlock = faults.fail(UNLOCK, 1);
                IOException originalClose = faults.fail(CLOSE, 1);
                IOException writableClose = faults.fail(CLOSE, 2);
                IOException actual = assertThrows(IOException.class, writer::close);
                assertSame(unlock, actual);
                assertArrayEquals(new Throwable[] {originalClose, writableClose}, actual.getSuppressed(),
                        "Both the original RO and promoted RW descriptors must attempt close");
                if (closeFirst) {
                    nativeDb.ok("BEGIN IMMEDIATE");
                    nativeDb.ok("ROLLBACK");
                } else {
                    nativeDb.busy("BEGIN IMMEDIATE");
                }
                assertReopenRejected(path);
                faults.closeDelegates();
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                nativeDb.ok("COMMIT");
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                assertReopenRejected(path);
            }
            proof.println("READY\tclose-fault\tcloseBeforeFailure=" + closeFirst
                    + "\tboth-descriptors-attempted\tnative-commit=31\treopen-quarantined");
            proof.flush();
            System.in.read();
        }

        private static void assertReopenRejected(Path path) {
            for (boolean readOnly : new boolean[] {true, false}) {
                assertThrows(IOException.class, () -> {
                    try (RollbackFile ignored = RollbackFile.open(path, readOnly)) {
                        fail("An ambiguous descriptor close must quarantine both reopen modes");
                    }
                });
            }
        }
    }
}
