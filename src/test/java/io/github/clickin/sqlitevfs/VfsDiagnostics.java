package io.github.clickin.sqlitevfs;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static io.github.clickin.sqlitevfs.RollbackFile.Level.*;
import static java.nio.file.StandardOpenOption.*;

/** Test-source executable: VfsDiagnostics [all|jfr|baseline] [iterations] [target/output-dir]. */
public final class VfsDiagnostics {
    private static final int PAGE = 4096;
    private static final int PAGES = 64;
    // SQLite 3.53.4 os.h. Raw baselines serialize requests, since FileChannel
    // cannot represent overlapping same-JVM SHARED grants without a coordinator.
    private static final long PENDING_BYTE = 0x40000000L;
    private static final long RESERVED_BYTE = PENDING_BYTE + 1;
    private static final long SHARED_FIRST = PENDING_BYTE + 2;
    private static final int SHARED_SIZE = 510;

    private VfsDiagnostics() {}

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() < 25) {
            throw new IllegalStateException("JDK 25 or later is required");
        }
        String mode = args.length == 0 ? "all" : args[0];
        if (args.length > 3 || !List.of("all", "jfr", "baseline").contains(mode)) {
            throw new IllegalArgumentException(
                    "VfsDiagnostics [all|jfr|baseline] [positive iterations] [target/output-dir]");
        }
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 128;
        if (iterations <= 0) throw new IllegalArgumentException("iterations must be positive");
        Path target = Path.of("target").toAbsolutePath().normalize();
        Path output = (args.length > 2 ? Path.of(args[2]) : target.resolve("diagnostics"))
                .toAbsolutePath().normalize();
        if (!output.startsWith(target)) {
            throw new IllegalArgumentException("Diagnostic artifacts must be under " + target);
        }
        Files.createDirectories(output);
        Path summary = output.resolve(mode + "-summary.txt");
        try (PrintWriter report = new PrintWriter(Files.newBufferedWriter(summary))) {
            report.println("java=" + System.getProperty("java.runtime.version"));
            report.println("os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            report.println("mode=" + mode + " iterations=" + iterations);
            report.println("Primitive measurements, not SQL benchmarks; no timing assertions.");
            report.println("Elapsed times include loop/check overhead; warmup/cache/filesystem effects are uncontrolled.");
            if (!mode.equals("baseline")) jfr(output, iterations, report);
            if (!mode.equals("jfr")) baseline(output, iterations, report);
            report.println("All workload behavior checks passed.");
            report.flush();
            if (report.checkError()) throw new IOException("Cannot write diagnostics summary");
        }
        System.out.println(Files.readString(summary));
        System.out.println("Artifacts: " + output);
    }

    private static void jfr(Path output, int iterations, PrintWriter report) throws Exception {
        require(FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .anyMatch(t -> t.getName().equals("jdk.VirtualThreadPinned")),
                "This JDK does not expose jdk.VirtualThreadPinned");
        Path recordingPath = output.resolve("vfs-jdk" + Runtime.version().feature() + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
            recording.start();
            workload(output, "platform", Thread.ofPlatform().name("vfs-platform-", 0).factory(),
                    8, iterations, report);
            workload(output, "one-vt", Thread.ofVirtual().name("vfs-one-vt-", 0).factory(),
                    1, iterations, report);
            workload(output, "many-vt", Thread.ofVirtual().name("vfs-many-vt-", 0).factory(),
                    64, iterations, report);
            recording.stop();
            recording.dump(recordingPath);
        }
        long pins = 0;
        long totalNanos = 0;
        long maxNanos = 0;
        long reads = 0;
        long writes = 0;
        try (RecordingFile recording = new RecordingFile(recordingPath)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String name = event.getEventType().getName();
                if (name.equals("jdk.FileRead")) reads++;
                if (name.equals("jdk.FileWrite")) writes++;
                if (!name.equals("jdk.VirtualThreadPinned")) continue;
                pins++;
                long duration = event.getDuration().toNanos();
                totalNanos += duration;
                maxNanos = Math.max(maxNanos, duration);
                report.println("pin start=" + event.getStartTime() + " duration_ns=" + duration
                        + " thread=" + (event.getThread() == null ? "unknown" : event.getThread().getJavaName()));
                RecordedStackTrace stack = event.getStackTrace();
                if (stack == null) report.println("  stack unavailable");
                else {
                    for (var frame : stack.getFrames()) {
                        var method = frame.getMethod();
                        report.println("  at " + method.getType().getName() + "." + method.getName()
                                + ":" + frame.getLineNumber() + " [" + frame.getType() + "]");
                    }
                    if (stack.isTruncated()) report.println("  (JFR stack truncated)");
                }
            }
        }
        report.printf("JFR pins=%d total_pin_ns=%d max_pin_ns=%d file_read_events=%d file_write_events=%d%n",
                pins, totalNanos, maxNanos, reads, writes);
        report.println("JDK 25 interpretation: synchronized no longer inherently pins virtual threads (JEP 491).");
        report.println("Zero recorded pins is not proof of nonblocking filesystem I/O or zero carrier occupancy.");
        report.println("Inspect event stacks/durations for avoidable blocking; this workload uses tryLock, never lock().");
        report.println("Harness owns and joins every worker; production does not create executors.");
        report.println("recording=" + recordingPath);
        report.flush();
    }

    private record Counts(long reads, long writes, long busy, long attempts, long forces) {}

    private static void workload(Path output, String name, ThreadFactory factory,
                                 int workers, int iterations, PrintWriter report) throws Exception {
        Path path = Files.createTempFile(output, "jfr-" + name + "-", ".db");
        try {
            try (RollbackFile initial = RollbackFile.open(path, false)) {
                ByteBuffer zero = ByteBuffer.allocate(PAGE);
                for (int worker = 0; worker < workers; worker++) {
                    zero.clear();
                    initial.write(zero, (long) worker * PAGE);
                }
            }
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Counts>> futures = new ArrayList<>();
            long before = System.nanoTime();
            long reads = 0, writes = 0, busy = 0, attempts = 0, forces = 0;
            // Test-owned threads only. Closing the executor joins every finite
            // task; never interrupt shared-channel I/O as a cleanup strategy.
            try (var executor = Executors.newThreadPerTaskExecutor(factory)) {
                for (int worker = 0; worker < workers; worker++) {
                    int slot = worker;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        require(start.await(30, TimeUnit.SECONDS), "workload start timeout");
                        return exercise(path, slot, iterations);
                    }));
                }
                try {
                    require(ready.await(30, TimeUnit.SECONDS), "workload readiness timeout");
                } finally {
                    start.countDown();
                }
                for (Future<Counts> future : futures) {
                    Counts count = future.get(90, TimeUnit.SECONDS);
                    reads += count.reads;
                    writes += count.writes;
                    busy += count.busy;
                    attempts += count.attempts;
                    forces += count.forces;
                }
            }
            // The per-slot sequence survives final descriptor close/reopen.
            try (RollbackFile check = RollbackFile.open(path, true)) {
                ByteBuffer data = ByteBuffer.allocate(PAGE);
                for (int worker = 0; worker < workers; worker++) {
                    data.clear();
                    require(check.read(data, (long) worker * PAGE) == RollbackFile.OK, "reopen short read");
                    verifyPage(data, futures.get(worker).get().writes);
                }
            }
            report.printf("workload=%s workers=%d iterations_per_worker=%d reads=%d writes=%d busy=%d lock_attempts=%d forces=%d elapsed_ns=%d%n",
                    name, workers, iterations, reads, writes, busy, attempts, forces,
                    System.nanoTime() - before);
            require(reads > 0, "workload did not exercise reads");
            if (workers == 1) {
                require(writes == iterations && busy == 0, "uncontended workload lost a write");
            }
            report.flush();
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static Counts exercise(Path path, int slot, int iterations) throws IOException {
        long reads = 0, writes = 0, busy = 0, attempts = 0, forces = 0;
        ByteBuffer data = ByteBuffer.allocate(PAGE);
        try (RollbackFile file = RollbackFile.open(path, false)) {
            for (int iteration = 0; iteration < iterations; iteration++) {
                attempts++;
                if (!file.lock(SHARED)) {
                    busy++;
                    Thread.yield();
                    continue;
                }
                try {
                    data.clear();
                    require(file.read(data, (long) slot * PAGE) == RollbackFile.OK, "stress short read");
                    verifyPage(data, writes);
                    reads++;
                    attempts++;
                    if (!file.lock(RESERVED)) {
                        busy++;
                        continue;
                    }
                    attempts++;
                    if (!file.lock(EXCLUSIVE)) {
                        busy++;
                        continue;
                    }
                    writes++;
                    fillPage(data, writes);
                    file.write(data, (long) slot * PAGE);
                    if (iteration % 64 == 0) {
                        file.sync(true);
                        forces++;
                    }
                } finally {
                    file.unlock(NONE);
                }
            }
        }
        return new Counts(reads, writes, busy, attempts, forces);
    }

    private static void fillPage(ByteBuffer buffer, long value) {
        buffer.clear();
        while (buffer.hasRemaining()) buffer.putLong(value);
        buffer.flip();
    }

    private static void verifyPage(ByteBuffer buffer, long expected) {
        buffer.flip();
        while (buffer.hasRemaining()) require(buffer.getLong() == expected, "page contents diverged");
    }

    private static void baseline(Path output, int iterations, PrintWriter report) throws Exception {
        for (boolean raw : new boolean[]{true, false}) {
            ioBaseline(output, raw, iterations, report);
        }
        report.println("Raw lock comparison is serialized, same SQLite byte ranges and transition syscalls.");
        report.println("Raw NIO cannot offer concurrent overlapping same-JVM SHARED locks; fan-in rows are VFS only.");
        for (int handles : new int[]{1, 8, 64, 256}) {
            lockBaseline(output, true, handles, iterations, report);
            lockBaseline(output, false, handles, iterations, report);
            fanInBaseline(output, handles, Math.max(1, iterations / handles), report);
        }
    }

    private static void ioBaseline(Path output, boolean raw, int iterations, PrintWriter report)
            throws IOException {
        Path path = Files.createTempFile(output, "io-", ".db");
        byte[] expected = new byte[PAGE];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 31 + 7);
        ByteBuffer buffer = ByteBuffer.allocate(PAGE);
        int[] randomPages = new Random(0x5EED).ints(iterations, 0, PAGES).toArray();
        String implementation = raw ? "raw-nio" : "rollback-vfs";
        try (FileChannel channel = raw ? FileChannel.open(path, READ, WRITE) : null;
             RollbackFile file = raw ? null : RollbackFile.open(path, false)) {
            for (int page = 0; page < PAGES; page++) {
                buffer.clear().put(expected).flip();
                write(file, channel, buffer, (long) page * PAGE);
            }
            for (String operation : List.of("seq-read", "random-read", "seq-write", "random-write")) {
                boolean reading = operation.endsWith("read");
                boolean random = operation.startsWith("random");
                long before = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    long offset = (long) (random ? randomPages[i] : i % PAGES) * PAGE;
                    buffer.clear();
                    if (reading) {
                        read(file, channel, buffer, offset);
                        require(Arrays.equals(expected, buffer.array()), "baseline read corruption");
                    } else {
                        buffer.put(expected).flip();
                        write(file, channel, buffer, offset);
                    }
                }
                measurement(report, implementation, operation, 1, iterations,
                        (long) iterations * PAGE, System.nanoTime() - before);
            }
            for (int page = 0; page < PAGES; page++) {
                buffer.clear();
                read(file, channel, buffer, (long) page * PAGE);
                require(Arrays.equals(expected, buffer.array()), "baseline write corruption");
            }
            long before = System.nanoTime();
            int forces = Math.min(8, iterations);
            for (int i = 0; i < forces; i++) {
                if (raw) channel.force(true);
                else file.sync(true);
            }
            measurement(report, implementation, "force-metadata", 1, forces, 0, System.nanoTime() - before);
            ByteBuffer zero = ByteBuffer.allocate(1);
            before = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                long size = (long) (i % 2 == 0 ? PAGES / 2 : PAGES) * PAGE;
                if (raw) {
                    if (size > channel.size()) {
                        zero.clear();
                        write(null, channel, zero, size - 1);
                    } else channel.truncate(size);
                } else file.truncate(size);
                require((raw ? channel.size() : file.size()) == size, "truncate size mismatch");
            }
            measurement(report, implementation, "truncate-shrink-extend", 1, iterations, 0,
                    System.nanoTime() - before);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void read(RollbackFile file, FileChannel channel, ByteBuffer buffer, long offset)
            throws IOException {
        if (file != null) {
            require(file.read(buffer, offset) == RollbackFile.OK, "baseline short read");
            return;
        }
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, offset);
            require(count > 0, "raw read made no progress");
            offset += count;
        }
    }

    private static void write(RollbackFile file, FileChannel channel, ByteBuffer buffer, long offset)
            throws IOException {
        if (file != null) {
            file.write(buffer, offset);
            return;
        }
        while (buffer.hasRemaining()) {
            int count = channel.write(buffer, offset);
            require(count > 0, "raw write made no progress");
            offset += count;
        }
    }

    private static void lockBaseline(Path output, boolean raw, int handles, int iterations,
                                     PrintWriter report) throws Exception {
        Path path = Files.createTempFile(output, "locks-", ".db");
        List<AutoCloseable> opened = new ArrayList<>();
        try {
            for (int i = 0; i < handles; i++) {
                opened.add(raw ? FileChannel.open(path, READ, WRITE) : RollbackFile.open(path, false));
            }
            for (RollbackFile.Level level : List.of(SHARED, RESERVED, EXCLUSIVE)) {
                long before = System.nanoTime();
                for (int iteration = 0; iteration < iterations; iteration++) {
                    for (AutoCloseable handle : opened) {
                        if (raw) rawCycle((FileChannel) handle, level);
                        else {
                            RollbackFile file = (RollbackFile) handle;
                            require(file.lock(SHARED), "uncontended SHARED busy");
                            try {
                                if (level != SHARED) require(file.lock(RESERVED), "uncontended RESERVED busy");
                                if (level == EXCLUSIVE) require(file.lock(EXCLUSIVE), "uncontended EXCLUSIVE busy");
                                require(file.level() == level, "wrong granted level");
                            } finally {
                                file.unlock(NONE);
                            }
                        }
                    }
                }
                measurement(report, raw ? "raw-nio-serialized" : "rollback-vfs-serialized",
                        "lock-cycle-" + level, handles, (long) handles * iterations, 0,
                        System.nanoTime() - before);
            }
        } finally {
            closeAll(opened, path);
        }
    }

    private static void rawCycle(FileChannel channel, RollbackFile.Level level) throws IOException {
        FileLock range = null;
        FileLock reserved = null;
        FileLock pending = null;
        try {
            try (FileLock admission = rawLock(channel, PENDING_BYTE, 1, true)) {
                range = rawLock(channel, SHARED_FIRST, SHARED_SIZE, true);
            }
            if (level != SHARED) reserved = rawLock(channel, RESERVED_BYTE, 1, false);
            if (level == EXCLUSIVE) {
                pending = rawLock(channel, PENDING_BYTE, 1, false);
                range.close();
                range = null;
                range = rawLock(channel, SHARED_FIRST, SHARED_SIZE, false);
            }
        } finally {
            try {
                if (range != null) range.close();
            } finally {
                try {
                    if (reserved != null) reserved.close();
                } finally {
                    if (pending != null) pending.close();
                }
            }
        }
    }

    private static FileLock rawLock(FileChannel channel, long offset, long size, boolean shared)
            throws IOException {
        FileLock lock = channel.tryLock(offset, size, shared);
        require(lock != null, "uncontended raw lock busy");
        if (shared && !lock.isShared()) {
            lock.close();
            throw new IOException("Filesystem did not grant a real shared lock");
        }
        return lock;
    }

    private static void fanInBaseline(Path output, int handles, int rounds, PrintWriter report)
            throws Exception {
        Path path = Files.createTempFile(output, "fanin-", ".db");
        List<RollbackFile> files = new ArrayList<>();
        long attempts = 0;
        long busy = 0;
        try {
            for (int i = 0; i < handles; i++) files.add(RollbackFile.open(path, false));
            long before = System.nanoTime();
            for (int round = 0; round < rounds; round++) {
                for (RollbackFile file : files) {
                    attempts++;
                    require(file.lock(SHARED), "coexisting SHARED rejected");
                }
                RollbackFile writer = files.get(round % handles);
                attempts++;
                require(writer.lock(RESERVED), "first writer rejected");
                for (RollbackFile file : files) {
                    if (file == writer) continue;
                    attempts++;
                    require(!file.lock(RESERVED), "two RESERVED owners");
                    busy++;
                }
                attempts++;
                boolean exclusive = writer.lock(EXCLUSIVE);
                require(exclusive == (handles == 1), "EXCLUSIVE/reader conflict");
                if (!exclusive) busy++;
                for (RollbackFile file : files) if (file != writer) file.unlock(NONE);
                attempts++;
                require(writer.lock(EXCLUSIVE), "promotion failed after last reader");
                writer.unlock(SHARED);
                require(!writer.checkReservedLock(), "downgrade retained write intent");
                writer.unlock(NONE);
            }
            measurement(report, "rollback-vfs-fanin", "shared-reserved-exclusive-downgrade",
                    handles, rounds, 0, System.nanoTime() - before);
            report.printf("fanin handles=%d lock_attempts=%d expected_busy=%d%n", handles, attempts, busy);
        } finally {
            closeAll(files, path);
        }
    }

    private static void closeAll(List<? extends AutoCloseable> opened, Path path) throws Exception {
        Exception failure = null;
        for (AutoCloseable handle : opened.reversed()) {
            try {
                handle.close();
            } catch (Exception cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure != null) throw failure;
    }

    private static void measurement(PrintWriter report, String implementation, String operation,
                                    int handles, long count, long bytes, long nanos) {
        report.printf("impl=%s op=%s handles=%d count=%d bytes=%d elapsed_ns=%d ns_per_op=%.1f%n",
                implementation, operation, handles, count, bytes, nanos, (double) nanos / count);
        report.flush();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
