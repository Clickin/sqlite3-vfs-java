package io.github.clickin.sqlitevfs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.FileSystemLoopException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.random.RandomGenerator;

import static io.github.clickin.sqlitevfs.SqliteCodes.*;

/**
 * SQLite rollback-VFS semantics over the default Java filesystem provider.
 * No engine, native extension loader, WAL shared memory or mmap is provided.
 * Adapters must advertise sqlite3_io_methods version 1.
 *
 * <p>All database opens in the JVM must use the same RollbackFile classloader.
 * Paths must remain stable while open: concurrent rename/unlink/replacement is
 * unsupported, as Java cannot read an open channel's filesystem identity.
 * Hard-link aliases coordinate locks, not journal names. Network filesystems
 * require independent lock/durability qualification; no capability is inferred.
 *
 * <p>FileChannel.force is the durability boundary, not a promise of hardware
 * power-loss protection or macOS F_FULLFSYNC. Explicit directory synchronization
 * is strict and may fail on a provider (notably Windows). Like SQLite's winSync,
 * ordinary Windows journal sync does not request directory synchronization.
 */
public final class NioVfs {
    public record OpenResult(int code, File file, int flags) {}
    public record IntResult(int code, int value) {}
    public record LongResult(int code, long value) {}
    public record PathResult(int code, String path) {}

    private static final long JULIAN_UNIX_EPOCH_MILLIS = 210_866_760_000_000L;
    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final int TYPES = SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_TEMP_DB
            | SQLITE_OPEN_TRANSIENT_DB | SQLITE_OPEN_MAIN_JOURNAL | SQLITE_OPEN_TEMP_JOURNAL
            | SQLITE_OPEN_SUBJOURNAL | SQLITE_OPEN_SUPER_JOURNAL | SQLITE_OPEN_WAL;
    private static final int FLAGS = TYPES | SQLITE_OPEN_READONLY | SQLITE_OPEN_READWRITE
            | SQLITE_OPEN_CREATE | SQLITE_OPEN_DELETEONCLOSE | SQLITE_OPEN_EXCLUSIVE
            | SQLITE_OPEN_URI | SQLITE_OPEN_NOMUTEX | SQLITE_OPEN_FULLMUTEX
            | SQLITE_OPEN_SHAREDCACHE | SQLITE_OPEN_PRIVATECACHE | SQLITE_OPEN_NOFOLLOW
            | SQLITE_OPEN_EXRESCODE;
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    private final FileSystemOps fs;
    private final RollbackFile.ChannelOpener channels;
    private final Clock clock;
    private final RandomGenerator random;
    private final ThreadLocal<String> lastError = ThreadLocal.withInitial(() -> "");

    public NioVfs() {
        this(FileSystemOps.SYSTEM, FileChannel::open, Clock.systemUTC(), new SecureRandom());
    }

    NioVfs(FileSystemOps fs, RollbackFile.ChannelOpener channels, Clock clock,
           RandomGenerator random) {
        this.fs = Objects.requireNonNull(fs);
        this.channels = Objects.requireNonNull(channels);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    /** URI flags describe an already-decoded SQLite filename, not a Java URI. */
    public OpenResult open(String name, int flags) {
        boolean readOnly = (flags & SQLITE_OPEN_READONLY) != 0;
        boolean writable = (flags & SQLITE_OPEN_READWRITE) != 0;
        boolean create = (flags & SQLITE_OPEN_CREATE) != 0;
        boolean exclusive = (flags & SQLITE_OPEN_EXCLUSIVE) != 0;
        boolean delete = (flags & SQLITE_OPEN_DELETEONCLOSE) != 0;
        int type = flags & TYPES;
        if ((flags & ~FLAGS) != 0 || readOnly == writable || (create && !writable)
                || ((exclusive || delete) && !create) || Integer.bitCount(type) != 1
                || (name == null && !delete)
                || ((delete || name == null) && (type == SQLITE_OPEN_MAIN_DB
                || type == SQLITE_OPEN_MAIN_JOURNAL || type == SQLITE_OPEN_SUPER_JOURNAL
                || type == SQLITE_OPEN_WAL))) {
            return new OpenResult(error(SQLITE_MISUSE, "open", name, "Invalid or unsupported open flags"), null, 0);
        }
        Path path = null;
        boolean temporary = name == null;
        try {
            if (temporary) {
                path = fs.createTempFile();
            } else {
                Path input = path(name);
                if ((flags & SQLITE_OPEN_NOFOLLOW) != 0) {
                    canonical(input, true);
                }
                // CREATE_NEW must see the original final directory entry, including symlinks.
                path = exclusive ? input : canonical(input, false);
            }
            RollbackFile backend;
            try {
                backend = RollbackFile.open(path, readOnly, create && !temporary,
                        exclusive && !temporary, channels, fs);
            } catch (IOException writableFailure) {
                if (!writable || exclusive || delete) {
                    throw writableFailure;
                }
                try {
                    backend = RollbackFile.open(path, true, false, false, channels, fs);
                    flags = (flags & ~(SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE)) | SQLITE_OPEN_READONLY;
                } catch (IOException readFailure) {
                    writableFailure.addSuppressed(readFailure);
                    throw writableFailure;
                }
            }
            boolean directorySync = !WINDOWS && (flags & SQLITE_OPEN_CREATE) != 0
                    && (type == SQLITE_OPEN_MAIN_JOURNAL || type == SQLITE_OPEN_SUPER_JOURNAL
                    || type == SQLITE_OPEN_WAL);
            return new OpenResult(SQLITE_OK, new File(backend, path, flags, directorySync), flags);
        } catch (IOException | RuntimeException failure) {
            if (temporary && path != null) {
                try {
                    fs.delete(path);
                } catch (IOException | RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            int code = failure instanceof SymlinkRejected ? SQLITE_CANTOPEN_SYMLINK : SQLITE_CANTOPEN;
            if (!WINDOWS && create && path != null && failure instanceof AccessDeniedException
                    && (type == SQLITE_OPEN_MAIN_JOURNAL || type == SQLITE_OPEN_SUPER_JOURNAL
                    || type == SQLITE_OPEN_WAL)) {
                try {
                    fs.attributes(path);
                } catch (NoSuchFileException missing) {
                    // unixOpen distinguishes denied creation of a missing journal.
                    code = SQLITE_READONLY_DIRECTORY;
                } catch (IOException | RuntimeException metadataFailure) {
                    failure.addSuppressed(metadataFailure);
                }
            }
            return new OpenResult(error(code, "open", name, failure), null, 0);
        }
    }

    public int delete(String name, boolean syncDirectory) {
        Path path;
        try {
            path = path(name);
            if (fs.attributes(path, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                throw new IOException("Delete accepts files, not directories");
            }
            fs.delete(path);
        } catch (NoSuchFileException missing) {
            return error(SQLITE_IOERR_DELETE_NOENT, "delete", name, missing);
        } catch (IOException | RuntimeException failure) {
            return error(SQLITE_IOERR_DELETE, "delete", name, failure);
        }
        return syncDirectory ? syncDirectory(path) : SQLITE_OK;
    }

    public IntResult access(String name, int accessFlag) {
        if (accessFlag != SQLITE_ACCESS_EXISTS && accessFlag != SQLITE_ACCESS_READ
                && accessFlag != SQLITE_ACCESS_READWRITE) {
            return new IntResult(error(SQLITE_MISUSE, "access", name, "Invalid access flag"), 0);
        }
        try {
            Path path = path(name);
            if (accessFlag == SQLITE_ACCESS_EXISTS) {
                BasicFileAttributes attributes = fs.attributes(path);
                // unixAccess and winAccess treat an empty regular file as nonexistent.
                return new IntResult(SQLITE_OK, !attributes.isRegularFile() || attributes.size() > 0 ? 1 : 0);
            }
            try {
                if (accessFlag == SQLITE_ACCESS_READWRITE) {
                    fs.checkAccess(path, AccessMode.READ, AccessMode.WRITE);
                } else {
                    fs.checkAccess(path, AccessMode.READ);
                }
            } catch (AccessDeniedException denied) {
                return new IntResult(SQLITE_OK, 0);
            }
            return new IntResult(SQLITE_OK, 1);
        } catch (NoSuchFileException missing) {
            return new IntResult(SQLITE_OK, 0);
        } catch (IOException | RuntimeException failure) {
            return new IntResult(error(SQLITE_IOERR_ACCESS, "access", name, failure), 0);
        }
    }

    public PathResult fullPathname(String name) {
        try {
            return new PathResult(SQLITE_OK, canonical(path(name), false).toString());
        } catch (IOException | RuntimeException failure) {
            return new PathResult(error(SQLITE_CANTOPEN_FULLPATH, "fullPathname", name, failure), null);
        }
    }

    private static Path path(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("A nonempty filename is required");
        }
        return Path.of(name).toAbsolutePath();
    }

    private Path canonical(Path input, boolean noFollow) throws IOException {
        ArrayDeque<Path> remaining = new ArrayDeque<>();
        input.forEach(remaining::addLast);
        Path resolved = input.getRoot();
        int links = 0;
        while (!remaining.isEmpty()) {
            Path element = remaining.removeFirst();
            String text = element.toString();
            if (text.equals(".") || text.isEmpty()) {
                continue;
            }
            if (text.equals("..")) {
                if (resolved.getParent() != null) {
                    resolved = resolved.getParent();
                }
                continue;
            }
            Path candidate = resolved.resolve(element);
            BasicFileAttributes attributes;
            try {
                attributes = fs.attributes(candidate, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                resolved = candidate;
                continue;
            }
            if (attributes.isSymbolicLink()) {
                if (noFollow) {
                    throw new SymlinkRejected(candidate.toString());
                }
                if (++links > 200) {
                    throw new FileSystemLoopException(candidate.toString());
                }
                Path target = fs.readSymbolicLink(candidate);
                if (target.isAbsolute()) {
                    resolved = target.getRoot();
                }
                for (int index = target.getNameCount() - 1; index >= 0; index--) {
                    remaining.addFirst(target.getName(index));
                }
            } else {
                resolved = fs.realPath(candidate);
            }
        }
        return resolved;
    }

    private static final class SymlinkRejected extends IOException {
        SymlinkRejected(String path) { super("Symbolic link forbidden: " + path); }
    }

    private int syncDirectory(Path file) {
        try {
            fs.syncDirectory(file.toAbsolutePath().getParent());
            return SQLITE_OK;
        } catch (IOException | RuntimeException failure) {
            return error(SQLITE_IOERR_DIR_FSYNC, "syncDirectory", file, failure);
        }
    }

    /** Returns bytes supplied, not a result code; invalid destinations return zero. */
    public int randomness(ByteBuffer target) {
        if (target == null || target.isReadOnly()) {
            error(SQLITE_MISUSE, "randomness", null, "Writable buffer required");
            return 0;
        }
        int count = target.remaining();
        synchronized (random) {
            while (target.remaining() >= Long.BYTES) {
                target.putLong(random.nextLong());
            }
            if (target.hasRemaining()) {
                long bits = random.nextLong();
                while (target.hasRemaining()) {
                    target.put((byte) bits);
                    bits >>>= Byte.SIZE;
                }
            }
        }
        return count;
    }

    /** Sleeps without spinning; interruption preserves the flag and reports actual elapsed time. */
    public long sleep(long microseconds) {
        if (microseconds < 0) {
            error(SQLITE_MISUSE, "sleep", null, "Negative duration");
            return 0;
        }
        if (microseconds == 0) {
            return 0;
        }
        long start = System.nanoTime();
        try {
            Thread.sleep(microseconds / 1000, (int) (microseconds % 1000) * 1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            error(SQLITE_INTERRUPT, "sleep", null, interrupted);
        }
        return Math.max(0, (System.nanoTime() - start) / 1000);
    }

    public long currentTimeMillisJulian() {
        return Math.addExact(clock.millis(), JULIAN_UNIX_EPOCH_MILLIS);
    }

    public double currentTimeJulian() {
        return currentTimeMillisJulian() / (double) MILLIS_PER_DAY;
    }

    public Object dlOpen(String name) {
        error(SQLITE_ERROR, "dlOpen", name, "Native SQLite extensions are unsupported");
        return null;
    }

    public Object dlSym(Object handle, String symbol) {
        error(SQLITE_ERROR, "dlSym", symbol, "Native SQLite extensions are unsupported");
        return null;
    }

    public String dlError() { return "Native SQLite extensions are unsupported"; }
    public void dlClose(Object handle) { /* No native library handle can be created. */ }
    public String lastError() { return lastError.get(); }

    private int error(int code, String operation, Object path, Object detail) {
        StringBuilder message = new StringBuilder(operation).append(" [").append(code).append(']');
        if (path != null) {
            message.append(' ').append(path);
        }
        message.append(": ").append(detail);
        if (detail instanceof Throwable failure) {
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                message.append("; caused by ").append(cause);
            }
            for (Throwable suppressed : failure.getSuppressed()) {
                message.append("; suppressed ").append(suppressed);
            }
        }
        lastError.set(message.toString());
        return code;
    }

    public final class File {
        private final RollbackFile backend;
        private final Path path;
        private final boolean deleteOnClose;
        private boolean directorySync;
        private boolean closed;
        private int closeCode;

        private File(RollbackFile backend, Path path, int flags, boolean directorySync) {
            this.backend = backend;
            this.path = path;
            this.deleteOnClose = (flags & SQLITE_OPEN_DELETEONCLOSE) != 0;
            this.directorySync = directorySync;
        }

        public synchronized int read(ByteBuffer target, long offset) {
            if (!validRange(target, offset) || target.isReadOnly()) {
                return error(SQLITE_MISUSE, "read", path, "Invalid destination or file range");
            }
            try {
                int code = backend.read(target, offset);
                return code == SQLITE_OK ? code : error(code, "read", path, "End of file; unread suffix zero-filled");
            } catch (IOException | RuntimeException failure) {
                return error(SQLITE_IOERR_READ, "read", path, failure);
            }
        }

        public synchronized int write(ByteBuffer source, long offset) {
            if (!validRange(source, offset)) {
                return error(SQLITE_MISUSE, "write", path, "Invalid source or file range");
            }
            try {
                backend.write(source, offset);
                return SQLITE_OK;
            } catch (IOException | RuntimeException failure) {
                // Java exposes no portable errno; never guess SQLITE_FULL from exception text.
                return error(failure instanceof RollbackFile.ReadOnlyException ? SQLITE_READONLY : SQLITE_IOERR_WRITE,
                        "write", path, failure);
            }
        }

        public synchronized int truncate(long size) {
            if (size < 0) {
                return error(SQLITE_MISUSE, "truncate", path, "Negative size");
            }
            try {
                backend.truncate(size);
                return SQLITE_OK;
            } catch (IOException | RuntimeException failure) {
                return error(failure instanceof RollbackFile.ReadOnlyException ? SQLITE_READONLY : SQLITE_IOERR_TRUNCATE,
                        "truncate", path, failure);
            }
        }

        public synchronized int sync(int sqliteSyncFlags) {
            int mode = sqliteSyncFlags & 0x0f;
            if ((mode != SQLITE_SYNC_NORMAL && mode != SQLITE_SYNC_FULL)
                    || (sqliteSyncFlags & ~(0x0f | SQLITE_SYNC_DATAONLY)) != 0) {
                return error(SQLITE_MISUSE, "sync", path, "Invalid sync flags");
            }
            try {
                // FULL strengthens Java's metadata request, not its hardware guarantees.
                backend.sync(mode == SQLITE_SYNC_FULL || (sqliteSyncFlags & SQLITE_SYNC_DATAONLY) == 0);
            } catch (IOException | RuntimeException failure) {
                return error(SQLITE_IOERR_FSYNC, "sync", path, failure);
            }
            if (directorySync) {
                int code = syncDirectory(path);
                if (code != SQLITE_OK) {
                    return code;
                }
                directorySync = false;
            }
            return SQLITE_OK;
        }

        public synchronized LongResult fileSize() {
            try {
                return new LongResult(SQLITE_OK, backend.size());
            } catch (IOException | RuntimeException failure) {
                return new LongResult(error(SQLITE_IOERR_FSTAT, "fileSize", path, failure), 0);
            }
        }

        public synchronized int lock(int sqliteLockLevel) {
            RollbackFile.Level requested = switch (sqliteLockLevel) {
                case SQLITE_LOCK_SHARED -> RollbackFile.Level.SHARED;
                case SQLITE_LOCK_RESERVED -> RollbackFile.Level.RESERVED;
                case SQLITE_LOCK_EXCLUSIVE -> RollbackFile.Level.EXCLUSIVE;
                default -> null;
            };
            if (requested == null) {
                return error(SQLITE_MISUSE, "lock", path, "Request SHARED, RESERVED or EXCLUSIVE");
            }
            try {
                return backend.lock(requested) ? SQLITE_OK : SQLITE_BUSY;
            } catch (IllegalArgumentException invalid) {
                return error(SQLITE_MISUSE, "lock", path, invalid);
            } catch (IOException | RuntimeException failure) {
                int code = failure instanceof RollbackFile.ReadOnlyException ? SQLITE_READONLY
                        : sqliteLockLevel == SQLITE_LOCK_SHARED ? SQLITE_IOERR_RDLOCK : SQLITE_IOERR_LOCK;
                return error(code, "lock", path, failure);
            }
        }

        public synchronized int unlock(int sqliteLockLevel) {
            if (sqliteLockLevel != SQLITE_LOCK_NONE && sqliteLockLevel != SQLITE_LOCK_SHARED) {
                return error(SQLITE_MISUSE, "unlock", path, "Unlock to NONE or SHARED");
            }
            try {
                backend.unlock(sqliteLockLevel == SQLITE_LOCK_NONE ? RollbackFile.Level.NONE : RollbackFile.Level.SHARED);
                return SQLITE_OK;
            } catch (IOException | RuntimeException failure) {
                return error(SQLITE_IOERR_UNLOCK, "unlock", path, failure);
            }
        }

        public synchronized IntResult checkReservedLock() {
            try {
                return new IntResult(SQLITE_OK, backend.checkReservedLock() ? 1 : 0);
            } catch (IOException | RuntimeException failure) {
                return new IntResult(error(SQLITE_IOERR_CHECKRESERVEDLOCK, "checkReservedLock", path, failure), 0);
            }
        }

        public synchronized LongResult fileControl(int opcode, long arg) {
            if (closed) {
                return new LongResult(error(SQLITE_IOERR, "fileControl", path, "File is closed"), 0);
            }
            try {
                return switch (opcode) {
                    case SQLITE_FCNTL_LOCKSTATE -> new LongResult(SQLITE_OK, backend.level().ordinal());
                    case SQLITE_FCNTL_MMAP_SIZE -> new LongResult(SQLITE_OK, 0);
                    case SQLITE_FCNTL_POWERSAFE_OVERWRITE -> new LongResult(arg <= 0 ? SQLITE_OK : SQLITE_NOTFOUND, 0);
                    default -> new LongResult(SQLITE_NOTFOUND, 0);
                };
            } catch (RuntimeException failure) {
                return new LongResult(error(SQLITE_IOERR, "fileControl", path, failure), 0);
            }
        }

        /** SQLite 3.53.4 os.h fallback, not a detected physical sector size. */
        public int sectorSize() { return 4096; }
        public int deviceCharacteristics() { return 0; }

        public synchronized int close() {
            if (closed) {
                return closeCode;
            }
            closed = true;
            Throwable failure = null;
            try {
                backend.close();
            } catch (IOException | RuntimeException closeFailure) {
                failure = closeFailure;
            }
            if (deleteOnClose) {
                try {
                    fs.delete(path);
                } catch (NoSuchFileException alreadyGone) {
                    // The delete-on-close postcondition already holds.
                } catch (IOException | RuntimeException deleteFailure) {
                    if (failure == null) {
                        failure = deleteFailure;
                    } else {
                        failure.addSuppressed(deleteFailure);
                    }
                }
            }
            closeCode = failure == null ? SQLITE_OK : error(SQLITE_IOERR_CLOSE, "close", path, failure);
            return closeCode;
        }
    }

    private static boolean validRange(ByteBuffer buffer, long offset) {
        return buffer != null && offset >= 0 && offset <= Long.MAX_VALUE - buffer.remaining();
    }
}
