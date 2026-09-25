package io.github.clickin.sqlitevfs;

import io.github.clickin.sqlitevfs.RollbackFileTest.Child;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.clickin.sqlitevfs.RollbackFile.Level.EXCLUSIVE;
import static io.github.clickin.sqlitevfs.RollbackFile.Level.NONE;
import static io.github.clickin.sqlitevfs.RollbackFile.Level.PENDING;
import static io.github.clickin.sqlitevfs.RollbackFile.Level.RESERVED;
import static io.github.clickin.sqlitevfs.RollbackFile.Level.SHARED;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 90, unit = TimeUnit.SECONDS)
class RollbackFileConcurrencyTest {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "simultaneous readers and reserved contender, virtualThreads={0}")
    @ValueSource(booleans = {false, true})
    void simultaneousSharedHoldersElectOneReservedOwnerUntilAllContendersFinish(boolean virtual)
            throws Exception {
        Path path = directory.resolve("contenders.db");
        try (Child nativeDb = Child.nativeDb(path, true)) {
            ExecutorService executor = executor(virtual, 3);
            CyclicBarrier start = new CyclicBarrier(3);
            CountDownLatch shared = new CountDownLatch(3);
            CountDownLatch reserve = new CountDownLatch(1);
            CountDownLatch attempted = new CountDownLatch(3);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            List<Future<Boolean>> contenders = new ArrayList<>();
            try {
                for (int i = 0; i < 3; i++) {
                    contenders.add(executor.submit(() -> {
                        try (RollbackFile file = RollbackFile.open(path, false)) {
                            await(start);
                            assertTrue(file.lock(SHARED));
                            shared.countDown();
                            await(reserve);
                            boolean won = file.lock(RESERVED);
                            if (won) {
                                winners.incrementAndGet();
                            }
                            attempted.countDown();
                            // Keep the winner held until every attempt and native probe completes.
                            await(release);
                            return won;
                        }
                    }));
                }
                await(shared);
                assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
                nativeDb.busy("BEGIN EXCLUSIVE");
                reserve.countDown();
                await(attempted);
                assertEquals(1, winners.get());
                nativeDb.busy("BEGIN IMMEDIATE");
                assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
                release.countDown();
                int completedWinners = 0;
                for (Future<Boolean> contender : contenders) {
                    if (contender.get(30, TimeUnit.SECONDS)) {
                        completedWinners++;
                    }
                }
                assertEquals(1, completedWinners);
                nativeDb.ok("BEGIN EXCLUSIVE");
                nativeDb.ok("ROLLBACK");
            } finally {
                reserve.countDown();
                release.countDown();
                shutdown(executor);
            }
        }
    }

    @ParameterizedTest(name = "pending blocks arriving readers during departure, virtualThreads={0}")
    @ValueSource(booleans = {false, true})
    void pendingWriterBlocksLocalAndNativeArrivalsWhileExistingReadersLeave(boolean virtual)
            throws Exception {
        Path path = directory.resolve("pending.db");
        try (Child oldNativeReader = Child.nativeDb(path, true);
             Child arrivingNativeReader = Child.nativeDb(path, false);
             RollbackFile writer = RollbackFile.open(path, false);
             RollbackFile oldLocalReader = RollbackFile.open(path, true);
             RollbackFile arrivingLocalReader = RollbackFile.open(path, true)) {
            oldNativeReader.ok("BEGIN");
            assertEquals("30", oldNativeReader.ok("SELECT sum(value) FROM sample"));
            assertTrue(oldLocalReader.lock(SHARED));
            assertTrue(writer.lock(SHARED));
            assertTrue(writer.lock(RESERVED));
            assertFalse(writer.lock(EXCLUSIVE));
            assertEquals(PENDING, writer.level());
            arrivingNativeReader.busy("SELECT sum(value) FROM sample");
            assertEquals("30", oldNativeReader.ok("SELECT sum(value) FROM sample"));
            ByteBuffer header = ByteBuffer.allocate(16);
            assertEquals(RollbackFile.OK, oldLocalReader.read(header, 0));
            assertArrayEquals("SQLite format 3\0".getBytes(StandardCharsets.US_ASCII), header.array());

            ExecutorService executor = executor(virtual, 2);
            CyclicBarrier race = new CyclicBarrier(2);
            try {
                Future<?> departure = executor.submit(() -> {
                    await(race);
                    oldLocalReader.unlock(NONE);
                    return null;
                });
                Future<Boolean> arrival = executor.submit(() -> {
                    await(race);
                    return arrivingLocalReader.lock(SHARED);
                });
                departure.get(30, TimeUnit.SECONDS);
                assertFalse(arrival.get(30, TimeUnit.SECONDS));
            } finally {
                shutdown(executor);
            }
            arrivingNativeReader.busy("SELECT sum(value) FROM sample");
            assertFalse(writer.lock(EXCLUSIVE), "the original native reader is still active");
            oldNativeReader.ok("COMMIT");
            assertTrue(writer.lock(EXCLUSIVE));
            assertFalse(arrivingLocalReader.lock(SHARED));
            arrivingNativeReader.busy("SELECT sum(value) FROM sample");
            arrivingNativeReader.busy("BEGIN IMMEDIATE");
            writer.unlock(SHARED);
            assertTrue(arrivingLocalReader.lock(SHARED));
            assertEquals("30", arrivingNativeReader.ok("SELECT sum(value) FROM sample"));
            arrivingNativeReader.ok("BEGIN IMMEDIATE");
            arrivingNativeReader.ok("UPDATE sample SET value=value+1 WHERE id=1");
            arrivingNativeReader.busy("COMMIT");
            arrivingLocalReader.unlock(NONE);
            writer.unlock(NONE);
            arrivingNativeReader.ok("COMMIT");
            assertEquals("31", arrivingNativeReader.ok("SELECT sum(value) FROM sample"));
        }
    }

    @ParameterizedTest(name = "close races downgrade without losing native locks, virtualThreads={0}")
    @ValueSource(booleans = {false, true})
    void closingOriginalReadOnlyHandleDuringDowngradeKeepsNativeLocksSafeAcrossReopen(boolean virtual)
            throws Exception {
        Path path = directory.resolve("close-downgrade.db");
        try (Child nativeDb = Child.nativeDb(path, true)) {
            // RO first exercises the two-descriptor lifetime after promotion to RW.
            try (RollbackFile original = RollbackFile.open(path, true);
                 RollbackFile writer = RollbackFile.open(path, false)) {
                assertTrue(writer.lock(SHARED));
                assertTrue(writer.lock(RESERVED));
                assertTrue(writer.lock(EXCLUSIVE));
                nativeDb.busy("BEGIN IMMEDIATE");
                ExecutorService executor = executor(virtual, 2);
                CyclicBarrier race = new CyclicBarrier(2);
                try {
                    Future<?> close = executor.submit(() -> {
                        await(race);
                        original.close();
                        return null;
                    });
                    Future<?> downgrade = executor.submit(() -> {
                        await(race);
                        writer.unlock(SHARED);
                        return null;
                    });
                    close.get(30, TimeUnit.SECONDS);
                    downgrade.get(30, TimeUnit.SECONDS);
                } finally {
                    shutdown(executor);
                }
                assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
                nativeDb.busy("BEGIN EXCLUSIVE");
                nativeDb.ok("BEGIN IMMEDIATE");
                nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
                nativeDb.busy("COMMIT");
            }
            nativeDb.ok("COMMIT");
            assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
            try (RollbackFile reopened = RollbackFile.open(path, false)) {
                assertTrue(reopened.lock(SHARED));
                assertTrue(reopened.lock(RESERVED));
                nativeDb.busy("BEGIN IMMEDIATE");
                assertTrue(reopened.lock(EXCLUSIVE));
                nativeDb.busy("SELECT sum(value) FROM sample");
                reopened.unlock(SHARED);
                assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
                nativeDb.busy("BEGIN EXCLUSIVE");
            }
            nativeDb.ok("BEGIN EXCLUSIVE");
            nativeDb.ok("ROLLBACK");
        }
    }

    @ParameterizedTest(name = "competing byte writers preserve every increment, virtualThreads={0}")
    @ValueSource(booleans = {false, true})
    void competingHandlesSerializeRealWritesAndNativeSqliteSeesTheFinalBytes(boolean virtual)
            throws Exception {
        Path path = directory.resolve("writes.db");
        // SQLite's big-endian user_version header field, not an engine/recovery adapter.
        long userVersionOffset = 60;
        try (Child nativeDb = Child.nativeDb(path, true)) {
            assertEquals("0", nativeDb.ok("PRAGMA user_version"));
            ExecutorService executor = executor(virtual, 3);
            CyclicBarrier phase = new CyclicBarrier(3);
            List<Future<?>> writers = new ArrayList<>();
            try {
                for (int i = 0; i < 3; i++) {
                    writers.add(executor.submit(() -> {
                        try (RollbackFile file = RollbackFile.open(path, false)) {
                            ByteBuffer counter = ByteBuffer.allocate(Integer.BYTES);
                            for (int round = 0; round < 2; round++) {
                                boolean wrote = false;
                                for (int turn = 0; turn < 3; turn++) {
                                    if (!wrote) {
                                        assertTrue(file.lock(SHARED));
                                    }
                                    await(phase);
                                    boolean won = !wrote && file.lock(RESERVED);
                                    await(phase);
                                    if (!wrote && !won) {
                                        file.unlock(NONE);
                                    }
                                    await(phase);
                                    if (won) {
                                        assertTrue(file.lock(EXCLUSIVE));
                                        // Protect only the child protocol, never the file transaction.
                                        synchronized (nativeDb) {
                                            nativeDb.busy("BEGIN IMMEDIATE");
                                        }
                                        assertEquals(RollbackFile.OK,
                                                file.read(counter.clear(), userVersionOffset));
                                        int previous = counter.getInt(0);
                                        counter.clear().putInt(previous + 1).flip();
                                        file.write(counter, userVersionOffset);
                                        file.sync(false);
                                        file.unlock(NONE);
                                        wrote = true;
                                    }
                                    // Each winner sits out until all three handles have written.
                                    await(phase);
                                }
                                assertTrue(wrote, "every competing handle must contribute once per round");
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> writer : writers) {
                    writer.get(30, TimeUnit.SECONDS);
                }
            } finally {
                shutdown(executor);
            }
        }
        try (RollbackFile reopened = RollbackFile.open(path, true)) {
            assertTrue(reopened.lock(SHARED));
            ByteBuffer counter = ByteBuffer.allocate(Integer.BYTES);
            assertEquals(RollbackFile.OK, reopened.read(counter, userVersionOffset));
            assertArrayEquals(new byte[] {0, 0, 0, 6}, counter.array());
        }
        // A fresh native connection avoids accepting its previously cached database header.
        try (Child observer = Child.nativeDb(path, false)) {
            assertEquals("6", observer.ok("PRAGMA user_version"));
            assertEquals("30", observer.ok("SELECT sum(value) FROM sample"));
            observer.ok("BEGIN IMMEDIATE");
            observer.ok("UPDATE sample SET value=value+1 WHERE id=1");
            observer.ok("COMMIT");
            assertEquals("31", observer.ok("SELECT sum(value) FROM sample"));
        }
    }

    private static ExecutorService executor(boolean virtual, int workers) {
        return virtual ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newFixedThreadPool(workers);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(20, TimeUnit.SECONDS), "concurrent phase did not finish");
    }

    private static void await(CyclicBarrier barrier) throws Exception {
        barrier.await(20, TimeUnit.SECONDS);
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        try {
            assertTrue(executor.awaitTermination(25, TimeUnit.SECONDS), "concurrent workers did not finish");
        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "concurrent workers did not stop");
            }
        }
    }
}
