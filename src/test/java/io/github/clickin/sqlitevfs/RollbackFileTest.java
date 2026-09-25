package io.github.clickin.sqlitevfs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.github.clickin.sqlitevfs.RollbackFile.Level.EXCLUSIVE;
import static io.github.clickin.sqlitevfs.RollbackFile.Level.NONE;
import static io.github.clickin.sqlitevfs.RollbackFile.Level.PENDING;
import static io.github.clickin.sqlitevfs.RollbackFile.Level.RESERVED;
import static io.github.clickin.sqlitevfs.RollbackFile.Level.SHARED;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(value = 90, unit = TimeUnit.SECONDS)
class RollbackFileTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void reportEnvironment(@TempDir Path directory) throws IOException {
        System.out.printf("ENV java=%s vendor=%s vm=%s os=%s version=%s arch=%s%n",
                System.getProperty("java.version"), System.getProperty("java.vendor"),
                System.getProperty("java.vm.name"), System.getProperty("os.name"),
                System.getProperty("os.version"), System.getProperty("os.arch"));
        var store = Files.getFileStore(directory);
        System.out.printf("ENV FileStore=%s type=%s provider=%s%n", store.name(), store.type(),
                directory.getFileSystem().provider().getClass().getName());
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
            System.out.println("CAPABILITY directory force(true): completed without exception; not proof of power-loss durability");
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            System.out.println("CAPABILITY directory force(true): unavailable: " + exception);
        }
    }

    @Test
    void positionalIoZeroFillsOnlyTheRequestedShortReadAndTruncateCanExtend() throws Exception {
        Path path = directory.resolve("bytes");
        try (RollbackFile file = RollbackFile.open(path, false)) {
            ByteBuffer source = ByteBuffer.wrap(new byte[] {99, 1, 2, 3, 99});
            source.position(1).limit(4);
            file.write(source, 7);
            assertEquals(4, source.position());
            assertEquals(10, file.size());

            byte[] bytes = new byte[10];
            Arrays.fill(bytes, (byte) 85);
            ByteBuffer destination = ByteBuffer.wrap(bytes);
            destination.position(2).limit(8);
            assertEquals(RollbackFile.SHORT_READ, file.read(destination, 8));
            assertEquals(8, destination.position());
            assertArrayEquals(new byte[] {85, 85, 2, 3, 0, 0, 0, 0, 85, 85}, bytes);

            ByteBuffer beyondEnd = ByteBuffer.wrap(new byte[] {9, 9, 9});
            assertEquals(RollbackFile.SHORT_READ, file.read(beyondEnd, 100));
            assertArrayEquals(new byte[] {0, 0, 0}, beyondEnd.array());
            assertEquals(RollbackFile.OK, file.read(ByteBuffer.allocate(0), 100));
            file.sync(false);
            file.sync(true);
            System.out.println("IO ordinary file sync(false) and sync(true): completed");

            file.truncate(8);
            assertEquals(8, file.size());
            file.truncate(1024);
            assertEquals(1024, file.size());
            ByteBuffer extended = ByteBuffer.allocate(5);
            assertEquals(RollbackFile.OK, file.read(extended, 6));
            assertArrayEquals(new byte[] {0, 1, 0, 0, 0}, extended.array());
            ByteBuffer last = ByteBuffer.wrap(new byte[] {99});
            assertEquals(RollbackFile.OK, file.read(last, 1023));
            assertEquals(0, last.get(0));
        }
        try (RollbackFile reopened = RollbackFile.open(path, false)) {
            assertEquals(1024, reopened.size(), "writable reopen must not truncate");
        }
    }

    @Test
    void sparseOffsetsBeyondFourGiBAreNotNarrowedToInt() throws Exception {
        long offset = (1L << 32) + 17;
        try (RollbackFile file = RollbackFile.open(directory.resolve("sparse"), false)) {
            try {
                file.write(ByteBuffer.wrap(new byte[] {42}), offset);
                assertEquals(offset + 1, file.size());
                ByteBuffer bytes = ByteBuffer.wrap(new byte[] {9, 9, 9, 9, 9});
                assertEquals(RollbackFile.SHORT_READ, file.read(bytes, offset - 2));
                assertArrayEquals(new byte[] {0, 0, 42, 0, 0}, bytes.array());
                System.out.println("IO sparse positional write/read verified at offset=" + offset);
            } finally {
                file.truncate(0);
            }
        }
    }

    @Test
    void readOnlyProbeAndReadOnlyToWritableOpenKeepPerHandleCapabilities() throws Exception {
        Path path = directory.resolve("readonly");
        Files.write(path, new byte[] {7, 8, 9});
        try (RollbackFile reader = RollbackFile.open(path, true)) {
            assertTrue(reader.lock(SHARED));
            assertFalse(reader.checkReservedLock());
            assertThrows(IOException.class, () -> reader.write(ByteBuffer.wrap(new byte[] {0}), 0));
            assertThrows(IOException.class, () -> reader.truncate(0));
            try (RollbackFile writer = RollbackFile.open(path, false)) {
                assertTrue(writer.lock(SHARED));
                assertTrue(writer.lock(RESERVED));
                assertTrue(reader.checkReservedLock());
                writer.write(ByteBuffer.wrap(new byte[] {6}), 0);
                assertThrows(IOException.class, () -> reader.write(ByteBuffer.wrap(new byte[] {0}), 0));
            }
            assertEquals(SHARED, reader.level());
            assertFalse(reader.checkReservedLock());
            ByteBuffer bytes = ByteBuffer.allocate(3);
            assertEquals(RollbackFile.OK, reader.read(bytes, 0));
            assertArrayEquals(new byte[] {6, 8, 9}, bytes.array());
        }
        Path missing = directory.resolve("missing");
        assertThrows(IOException.class, () -> RollbackFile.open(missing, true));
        assertFalse(Files.exists(missing), "read-only open must not create a file");
    }

    @Test
    void reservedContendsInBothDirectionsAndSharedBlocksCommitNotImmediate() throws Exception {
        Path path = directory.resolve("reserved.db");
        try (Child nativeDb = Child.nativeDb(path, true);
             RollbackFile file = RollbackFile.open(path, false)) {
            nativeDb.ok("BEGIN IMMEDIATE");
            assertTrue(file.lock(SHARED));
            assertTrue(file.checkReservedLock());
            assertFalse(file.lock(RESERVED));
            assertEquals(SHARED, file.level());
            nativeDb.ok("ROLLBACK");
            assertFalse(file.checkReservedLock());
            assertTrue(file.lock(RESERVED));
            nativeDb.busy("BEGIN IMMEDIATE");
            file.unlock(SHARED);
            nativeDb.ok("BEGIN IMMEDIATE");
            nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
            nativeDb.busy("COMMIT");
            file.unlock(NONE);
            nativeDb.ok("COMMIT");
            assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
        }
    }

    @Test
    void exclusiveContendsInBothDirectionsAndDowngradesToShared() throws Exception {
        Path path = directory.resolve("exclusive.db");
        try (Child nativeDb = Child.nativeDb(path, true);
             RollbackFile file = RollbackFile.open(path, false)) {
            nativeDb.ok("BEGIN EXCLUSIVE");
            assertFalse(file.lock(SHARED));
            assertEquals(NONE, file.level());
            nativeDb.ok("ROLLBACK");
            assertTrue(file.lock(SHARED));
            assertTrue(file.lock(EXCLUSIVE));
            assertEquals(EXCLUSIVE, file.level());
            nativeDb.busy("SELECT sum(value) FROM sample");
            file.unlock(SHARED);
            assertEquals(SHARED, file.level());
            assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
            nativeDb.ok("BEGIN IMMEDIATE");
            nativeDb.ok("ROLLBACK");
            assertTrue(file.lock(EXCLUSIVE));
            file.unlock(NONE);
            assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
        }
    }

    @Test
    void nativeReaderForcesPendingAndPendingBlocksNewNativeReaders() throws Exception {
        Path path = directory.resolve("pending.db");
        try (Child oldReader = Child.nativeDb(path, true);
             Child newReader = Child.nativeDb(path, false);
             RollbackFile writer = RollbackFile.open(path, false)) {
            oldReader.ok("BEGIN");
            assertEquals("30", oldReader.ok("SELECT sum(value) FROM sample"));
            assertTrue(writer.lock(SHARED));
            assertTrue(writer.lock(RESERVED));
            assertFalse(writer.lock(EXCLUSIVE));
            assertEquals(PENDING, writer.level());
            newReader.busy("SELECT sum(value) FROM sample");
            assertEquals("30", oldReader.ok("SELECT sum(value) FROM sample"));
            oldReader.close();
            assertTrue(writer.lock(EXCLUSIVE), "reader process exit must release its OS locks");
            newReader.busy("SELECT sum(value) FROM sample");
            writer.unlock(SHARED);
            assertEquals("30", newReader.ok("SELECT sum(value) FROM sample"));
        }
    }

    @Test
    void closingNonownerReaderAndLogicalOwnerPreservesRemainingOwnersLocks() throws Exception {
        Path path = directory.resolve("local.db");
        try (Child nativeDb = Child.nativeDb(path, true);
             RollbackFile owner = RollbackFile.open(path, false);
             RollbackFile reader = RollbackFile.open(path, false)) {
            assertTrue(owner.lock(SHARED));
            assertTrue(reader.lock(SHARED));
            assertTrue(owner.lock(RESERVED));
            assertFalse(reader.lock(RESERVED));
            try (RollbackFile nonowner = RollbackFile.open(path, false)) {
                assertEquals(NONE, nonowner.level());
            }
            nativeDb.busy("BEGIN IMMEDIATE");
            try (RollbackFile retiringReader = RollbackFile.open(path, true)) {
                assertTrue(retiringReader.lock(SHARED));
            }
            nativeDb.busy("BEGIN IMMEDIATE");
            assertFalse(owner.lock(EXCLUSIVE));
            assertEquals(PENDING, owner.level());
            try (RollbackFile lateReader = RollbackFile.open(path, true)) {
                assertFalse(lateReader.lock(SHARED));
            }
            owner.close();
            owner.close();
            assertEquals(SHARED, reader.level());
            nativeDb.ok("BEGIN IMMEDIATE");
            nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
            nativeDb.busy("COMMIT");
            reader.close();
            nativeDb.ok("COMMIT");
            assertEquals("31", nativeDb.ok("SELECT sum(value) FROM sample"));
        }
    }

    @ParameterizedTest(name = "{0} aliases share logical locks and descriptor lifetime")
    @ValueSource(strings = {"symbolic", "hard"})
    void aliasesShareIdentityWithoutClosingAnotherHandlesOsLocks(String kind) throws Exception {
        Path path = directory.resolve("identity.db");
        Path alias = directory.resolve("alias.db");
        try (Child nativeDb = Child.nativeDb(path, true)) {
            try {
                if (kind.equals("symbolic")) {
                    Files.createSymbolicLink(alias, path.getFileName());
                } else {
                    Files.createLink(alias, path);
                }
            } catch (IOException | UnsupportedOperationException | SecurityException exception) {
                System.out.println("CAPABILITY " + kind + " link creation unavailable: " + exception);
                assumeTrue(false, "Link creation unsupported in this environment: " + exception);
            }
            try (RollbackFile owner = RollbackFile.open(path, false);
                 RollbackFile reader = RollbackFile.open(alias, true)) {
                assertTrue(owner.lock(SHARED));
                assertTrue(reader.lock(SHARED));
                assertTrue(owner.lock(RESERVED));
                assertTrue(reader.checkReservedLock());
                assertFalse(owner.lock(EXCLUSIVE));
                assertEquals(PENDING, owner.level());
                reader.close();
                nativeDb.busy("BEGIN IMMEDIATE");
                assertTrue(owner.lock(EXCLUSIVE));
                nativeDb.busy("SELECT sum(value) FROM sample");
                owner.unlock(NONE);
                assertEquals("30", nativeDb.ok("SELECT sum(value) FROM sample"));
            }
        }
    }

    @Test
    void readOnlyReservedProbeObservesExternalWriterWithoutDisturbingIt() throws Exception {
        Path path = directory.resolve("probe.db");
        try (Child nativeDb = Child.nativeDb(path, true);
             RollbackFile reader = RollbackFile.open(path, true)) {
            assertFalse(reader.checkReservedLock());
            nativeDb.ok("BEGIN IMMEDIATE");
            assertTrue(reader.checkReservedLock());
            assertTrue(reader.lock(SHARED));
            assertTrue(reader.checkReservedLock());
            nativeDb.ok("UPDATE sample SET value=value+1 WHERE id=1");
            nativeDb.busy("COMMIT");
            reader.unlock(NONE);
            nativeDb.ok("COMMIT");
            assertFalse(reader.checkReservedLock());
        }
    }

    @Test
    void backendProcessesContendAndReleasePendingWithoutNativeHelp() throws Exception {
        Path path = directory.resolve("peer");
        try (RollbackFile local = RollbackFile.open(path, false);
             Child peer = new Child(BackendPeer.class, path.toString())) {
            assertTrue(local.lock(SHARED));
            assertEquals("true", peer.ok("LOCK SHARED"));
            assertTrue(local.lock(RESERVED));
            assertEquals("false", peer.ok("LOCK RESERVED"));
            assertEquals("true", peer.ok("CHECK"));
            assertFalse(local.lock(EXCLUSIVE));
            assertEquals(PENDING, local.level());
            peer.ok("UNLOCK NONE");
            assertEquals("false", peer.ok("LOCK SHARED"));
            assertTrue(local.lock(EXCLUSIVE));
            local.unlock(SHARED);
            assertEquals("true", peer.ok("LOCK SHARED"));
            assertEquals("true", peer.ok("LOCK RESERVED"));
            assertEquals("false", peer.ok("LOCK EXCLUSIVE"));
            assertEquals("PENDING", peer.ok("LEVEL"));
            local.unlock(NONE);
            assertFalse(local.lock(SHARED));
            assertEquals("true", peer.ok("LOCK EXCLUSIVE"));
            peer.ok("UNLOCK NONE");
            assertTrue(local.lock(SHARED));
        }
    }

    @Test
    void virtualThreadUsesTheSameIoAndLockApi() throws Exception {
        Path path = directory.resolve("virtual");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            executor.submit(() -> {
                assertTrue(Thread.currentThread().isVirtual());
                try (RollbackFile file = RollbackFile.open(path, false)) {
                    file.write(ByteBuffer.wrap(new byte[] {4, 5, 6}), 0);
                    assertTrue(file.lock(SHARED));
                    assertTrue(file.lock(EXCLUSIVE));
                    file.sync(true);
                    file.unlock(SHARED);
                    ByteBuffer bytes = ByteBuffer.allocate(3);
                    assertEquals(RollbackFile.OK, file.read(bytes, 0));
                    assertArrayEquals(new byte[] {4, 5, 6}, bytes.array());
                    file.unlock(NONE);
                }
                return null;
            }).get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "virtual-thread task did not stop");
        }
    }

    /** Another JVM running only the Java backend, using the same bounded parent protocol. */
    public static final class BackendPeer {
        public static void main(String[] args) throws Exception {
            try (RollbackFile file = RollbackFile.open(Path.of(args[0]), false);
                 BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                 PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
                output.println("READY\tbackend\tjava=" + System.getProperty("java.version"));
                String line;
                while ((line = input.readLine()) != null) {
                    String[] request = line.split("\t", 2);
                    String[] command = NativeOracle.decode(request[1]).split(" ", 2);
                    String value = switch (command[0]) {
                        case "LOCK" -> Boolean.toString(file.lock(RollbackFile.Level.valueOf(command[1])));
                        case "UNLOCK" -> {
                            file.unlock(RollbackFile.Level.valueOf(command[1]));
                            yield "";
                        }
                        case "CHECK" -> Boolean.toString(file.checkReservedLock());
                        case "LEVEL" -> file.level().name();
                        default -> throw new IllegalArgumentException("Unknown backend command: " + command[0]);
                    };
                    NativeOracle.reply(output, request[0], 0, value);
                }
            }
        }
    }

    private static final class Child implements AutoCloseable {
        private static final String EOF = "<child stdout closed>";
        private final Process process;
        private final BufferedWriter input;
        private final BlockingQueue<String> output = new LinkedBlockingQueue<>();
        private int sequence;

        static Child nativeDb(Path path, boolean initialize) throws Exception {
            return new Child(NativeOracle.class, path.toString(), initialize ? "init" : "open");
        }

        Child(Class<?> mainClass, String... arguments) throws Exception {
            String classpath = System.getProperty("surefire.test.class.path");
            if (classpath == null || classpath.isBlank()) {
                classpath = System.getProperty("java.class.path");
            }
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            var command = new java.util.ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
            command.add("--enable-native-access=ALL-UNNAMED");
            command.add("-cp");
            command.add(classpath);
            command.add(mainClass.getName());
            command.addAll(Arrays.asList(arguments));
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread.ofVirtual().name("child-output-" + process.pid()).start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.add(line);
                    }
                } catch (IOException exception) {
                    output.add("<child stdout error: " + exception + ">");
                } finally {
                    output.add(EOF);
                }
            });
            try {
                String ready = receive();
                assertTrue(ready.startsWith("READY\t"), "Unexpected child handshake: " + ready);
                System.out.println("ORACLE pid=" + process.pid() + " " + ready);
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        String ok(String command) throws Exception {
            Response response = request(command);
            assertEquals(0, response.code(), command + ": " + response.value());
            return response.value();
        }

        void busy(String command) throws Exception {
            Response response = request(command);
            assertEquals(RollbackFile.BUSY, response.code(), command + ": " + response.value());
        }

        private Response request(String command) throws Exception {
            int id = ++sequence;
            input.write(id + "\t" + NativeOracle.encode(command));
            input.newLine();
            input.flush();
            String[] fields = receive().split("\t", 3);
            assertEquals(3, fields.length, "Malformed child response");
            assertEquals(Integer.toString(id), fields[0], "Out-of-sequence child response");
            Response response = new Response(Integer.parseInt(fields[1]), NativeOracle.decode(fields[2]));
            System.out.printf("ORACLE pid=%s command=%s code=%d value=%s%n",
                    process.pid(), command, response.code(), response.value());
            return response;
        }

        private String receive() throws InterruptedException {
            String line = output.poll(15, TimeUnit.SECONDS);
            assertNotNull(line, "Timed out waiting for child pid=" + process.pid());
            assertNotEquals(EOF, line, "Child exited before response, pid=" + process.pid());
            return line;
        }

        @Override
        public void close() {
            boolean interrupted = Thread.interrupted();
            process.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (process.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    assertTrue(remaining > 0, "Child did not exit: " + process.pid());
                    try {
                        process.waitFor(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            } finally {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // The process has already exited, so a broken stdin pipe is expected.
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private record Response(int code, String value) {}
    }
}
