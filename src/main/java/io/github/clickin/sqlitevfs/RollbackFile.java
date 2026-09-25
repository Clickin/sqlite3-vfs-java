package io.github.clickin.sqlitevfs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Positional file I/O and SQLite rollback-journal byte-range locks.
 *
 * <p>This is a file backend, not a SQL engine or a sqlite3_vfs C adapter. It
 * supports regular files on the default filesystem provider with stable paths
 * and real shared locks. WAL is not implemented.
 *
 * <p>All opens of a database in this JVM must use this class from the same
 * classloader. Uncoordinated descriptor closes can silently erase POSIX locks,
 * even while {@link FileLock#isValid()} remains true. Concurrent rename, unlink
 * or path replacement is unsupported: standard Java cannot obtain a file key
 * from an open channel to eliminate the path/open race. Hard links share locks
 * here, but this does
 * not make differently named SQLite journal sidecars interchangeable.
 *
 * <p>Do not interrupt a thread performing channel I/O. An interrupt may close a
 * shared channel, invalidating every local handle. Detected channel or lock loss
 * and lock-transition errors fail closed until all handles have been closed;
 * locks are never silently reacquired. Callers own transaction ordering and
 * retries. Operations on one handle serialize against its close, while file
 * I/O on distinct handles does not hold the lock coordinator's mutex.
 */
public final class RollbackFile implements AutoCloseable {
    public static final int OK = 0;
    public static final int BUSY = 5;
    public static final int SHORT_READ = 522;

    public enum Level { NONE, SHARED, RESERVED, PENDING, EXCLUSIVE }

    // SQLite 3.53.4 src/os.h: PENDING_BYTE, RESERVED_BYTE, SHARED_FIRST/SIZE.
    private static final long PENDING_BYTE = 0x40000000L;
    private static final long RESERVED_BYTE = PENDING_BYTE + 1;
    private static final long SHARED_FIRST = PENDING_BYTE + 2;
    private static final long SHARED_SIZE = 510;

    // ponytail: a global registry serializes open/final-close only. Per-file
    // mutexes protect lock transitions; split the registry only if needed.
    private static final Map<Object, State> FILES = new HashMap<>();

    private final State state;
    private final boolean readOnly;
    private Level level = Level.NONE;
    private boolean closed;
    private ByteBuffer extensionByte;

    private RollbackFile(State state, boolean readOnly) {
        this.state = state;
        this.readOnly = readOnly;
        state.handles++;
    }

    /** Opens without truncation; a writable open creates a missing file. */
    public static RollbackFile open(Path path, boolean readOnly) throws IOException {
        Objects.requireNonNull(path, "path");
        if (path.getFileSystem() != FileSystems.getDefault()) {
            throw new IOException("Only the default filesystem provider is supported");
        }
        synchronized (FILES) {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class);
            } catch (NoSuchFileException missing) {
                if (readOnly) {
                    throw missing;
                }
                FileChannel created;
                try {
                    created = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.READ, StandardOpenOption.WRITE,
                            StandardOpenOption.SPARSE);
                } catch (FileAlreadyExistsException raced) {
                    // Another process created it; inspect its identity before opening.
                    return open(path, false);
                }
                try {
                    Path realPath = path.toRealPath();
                    Object key = identity(realPath,
                            Files.readAttributes(realPath, BasicFileAttributes.class));
                    if (findState(key, realPath) != null) {
                        throw new IOException("File identity changed during creation");
                    }
                    State state = new State(key, realPath, created, true);
                    FILES.put(key, state);
                    return new RollbackFile(state, false);
                } catch (IOException | RuntimeException failure) {
                    try {
                        created.close();
                    } catch (IOException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }
            Path realPath = path.toRealPath();
            Object key = identity(realPath, attributes);
            State state = findState(key, realPath);
            if (state == null) {
                FileChannel channel = readOnly
                        ? FileChannel.open(realPath, StandardOpenOption.READ)
                        : FileChannel.open(realPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
                state = new State(key, realPath, channel, !readOnly);
                FILES.put(key, state);
                return new RollbackFile(state, readOnly);
            }
            state.mutex.lock();
            try {
                state.checkHealthy();
                if (!readOnly && state.writable == null) {
                    // SQLite os_unix.c UnixUnusedFd/closePendingFds and JDK FileLock:
                    // closing ANY descriptor for this inode may erase POSIX locks.
                    // Keep the original RO channel plus this RW channel until the
                    // final logical handle closes; never swap-and-close a channel.
                    state.writable = FileChannel.open(realPath,
                            StandardOpenOption.READ, StandardOpenOption.WRITE);
                }
                return new RollbackFile(state, readOnly);
            } finally {
                state.mutex.unlock();
            }
        }
    }

    private static Object identity(Path realPath, BasicFileAttributes attributes) throws IOException {
        if (!attributes.isRegularFile()) {
            throw new IOException("A regular file is required");
        }
        return attributes.fileKey() == null ? realPath : attributes.fileKey();
    }

    private static State findState(Object key, Path realPath) throws IOException {
        if (!(key instanceof Path)) {
            return FILES.get(key);
        }
        // OpenJDK 25 WindowsFileAttributes.fileKey() returns null. Its provider's
        // isSameFile compares volume serial/file index instead. Canonical paths
        // alone miss hard links; never substitute path equality for identity.
        // ponytail: O(open files) only on null-key providers; cache validated
        // aliases only if this scan becomes a measured bottleneck.
        for (State candidate : FILES.values()) {
            if (Files.isSameFile(realPath, candidate.path)) {
                return candidate;
            }
        }
        return null;
    }

    /** Reads the remaining buffer, advancing its position, zero-filling at EOF. */
    public synchronized int read(ByteBuffer dst, long offset) throws IOException {
        checkRange(dst, offset);
        if (dst.isReadOnly()) {
            throw new IllegalArgumentException("Read destination is read-only");
        }
        FileChannel channel = channel(false);
        while (dst.hasRemaining()) {
            int count = channel.read(dst, offset);
            if (count < 0) {
                while (dst.remaining() >= Long.BYTES) {
                    dst.putLong(0L);
                }
                while (dst.hasRemaining()) {
                    dst.put((byte) 0);
                }
                checkHealthy();
                return SHORT_READ;
            }
            if (count == 0) {
                throw new IOException("File read made no progress");
            }
            offset += count;
        }
        checkHealthy();
        return OK;
    }

    /** Writes the entire remaining buffer at the given offset. */
    public synchronized void write(ByteBuffer src, long offset) throws IOException {
        checkRange(src, offset);
        FileChannel channel = channel(true);
        writeFully(channel, src, offset);
        checkHealthy();
    }

    private static void checkRange(ByteBuffer buffer, long offset) {
        Objects.requireNonNull(buffer, "buffer");
        if (offset < 0 || offset > Long.MAX_VALUE - buffer.remaining()) {
            throw new IllegalArgumentException("Invalid file range");
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer src, long offset)
            throws IOException {
        while (src.hasRemaining()) {
            int count = channel.write(src, offset);
            if (count <= 0) {
                throw new IOException("File write made no progress");
            }
            offset += count;
        }
    }

    /** Shrinks or extends the file; callers must hold the appropriate write lock. */
    public synchronized void truncate(long size) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("Negative file size");
        }
        FileChannel channel = channel(true);
        if (size > channel.size()) {
            // FileChannel.truncate does not extend (JDK 25 FileChannel contract).
            if (extensionByte == null) {
                extensionByte = ByteBuffer.allocate(1);
            }
            extensionByte.clear();
            writeFully(channel, extensionByte, size - 1);
        } else {
            channel.truncate(size);
        }
        checkHealthy();
    }

    /** Forces file content and optionally metadata; does not sync the directory. */
    public synchronized void sync(boolean metadata) throws IOException {
        channel(false).force(metadata);
        checkHealthy();
    }

    public synchronized long size() throws IOException {
        long size = channel(false).size();
        checkHealthy();
        return size;
    }

    private FileChannel channel(boolean writing) throws IOException {
        state.mutex.lock();
        try {
            checkOpen();
            state.checkHealthy();
            if (writing && readOnly) {
                throw new IOException("Read-only handle");
            }
            return state.readable();
        } finally {
            state.mutex.unlock();
        }
    }

    private void checkHealthy() throws IOException {
        state.mutex.lock();
        try {
            state.checkHealthy();
        } finally {
            state.mutex.unlock();
        }
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("File handle is closed");
        }
    }

    /**
     * Nonblocking SHARED, RESERVED or EXCLUSIVE acquisition. A false result means
     * BUSY. RESERVED/EXCLUSIVE require an existing SHARED lock. Failed EXCLUSIVE
     * acquisition retains PENDING once obtained, blocking new readers until an
     * unlock. Repeated requests at or below the current level succeed.
     */
    public synchronized boolean lock(Level requested) throws IOException {
        if (requested != Level.SHARED && requested != Level.RESERVED
                && requested != Level.EXCLUSIVE) {
            throw new IllegalArgumentException("Request SHARED, RESERVED or EXCLUSIVE");
        }
        state.mutex.lock();
        try {
            checkOpen();
            state.checkHealthy();
            if (level.ordinal() >= requested.ordinal()) {
                return true;
            }
            if (readOnly && requested != Level.SHARED) {
                throw new IOException("Read-only handle cannot acquire a write lock");
            }
            if (level == Level.NONE && requested != Level.SHARED) {
                throw new IllegalArgumentException("Acquire SHARED before a write lock");
            }
            try {
                return switch (requested) {
                    case SHARED -> acquireShared();
                    case RESERVED -> acquireReserved();
                    case EXCLUSIVE -> acquireExclusive();
                    default -> throw new AssertionError(requested);
                };
            } catch (IOException failure) {
                throw state.fail(failure);
            }
        } finally {
            state.mutex.unlock();
        }
    }

    private boolean acquireShared() throws IOException {
        if (state.pendingOwner != null) {
            return false;
        }
        // SQLite 3.53.4 os_unix.c unixLock: read-lock PENDING, then the shared
        // range, then release PENDING. Probe even when joining local readers so
        // an external PENDING writer also prevents new logical readers.
        state.pending = state.tryLock(state.readable(), PENDING_BYTE, 1, true);
        if (state.pending == null) {
            return false;
        }
        try {
            if (state.range == null) {
                state.range = state.tryLock(state.readable(), SHARED_FIRST, SHARED_SIZE, true);
            }
        } finally {
            state.pending.release();
            state.pending = null;
        }
        if (state.range == null) {
            return false;
        }
        state.readers++;
        level = Level.SHARED;
        return true;
    }

    private boolean acquireReserved() throws IOException {
        if (state.reservedOwner != null || state.pendingOwner != null) {
            return false;
        }
        state.reserved = state.tryLock(state.writable, RESERVED_BYTE, 1, false);
        if (state.reserved == null) {
            return false;
        }
        state.reservedOwner = this;
        level = Level.RESERVED;
        return true;
    }

    private boolean acquireExclusive() throws IOException {
        if ((state.reservedOwner != null && state.reservedOwner != this)
                || (state.pendingOwner != null && state.pendingOwner != this)) {
            return false;
        }
        if (state.pendingOwner == null) {
            state.pending = state.tryLock(state.writable, PENDING_BYTE, 1, false);
            if (state.pending == null) {
                return false;
            }
            state.pendingOwner = this;
            level = Level.PENDING;
        }
        if (state.readers > 1) {
            return false;
        }
        // JDK FileLock cannot convert in place. Like SQLite 3.53.4 os_win.c
        // winLock/winUnlock, release/reacquire the shared range. Hold PENDING
        // throughout EVERY conversion, including direct SHARED -> EXCLUSIVE.
        state.range.release();
        state.range = null;
        state.range = state.tryLock(state.writable, SHARED_FIRST, SHARED_SIZE, false);
        if (state.range == null) {
            restoreShared();
            return false;
        }
        level = Level.EXCLUSIVE;
        return true;
    }

    private void restoreShared() throws IOException {
        state.range = state.tryLock(state.readable(), SHARED_FIRST, SHARED_SIZE, true);
        if (state.range == null) {
            throw new IOException("Lost shared lock during conversion; close all handles");
        }
    }

    /** Releases to NONE or downgrades to SHARED; a weaker current level is unchanged. */
    public synchronized void unlock(Level target) throws IOException {
        if (target != Level.NONE && target != Level.SHARED) {
            throw new IllegalArgumentException("Unlock to NONE or SHARED");
        }
        state.mutex.lock();
        try {
            checkOpen();
            state.checkHealthy();
            try {
                unlockTo(target);
            } catch (IOException failure) {
                throw state.fail(failure);
            }
        } finally {
            state.mutex.unlock();
        }
    }

    private void unlockTo(Level target) throws IOException {
        if (level.ordinal() <= target.ordinal()) {
            return;
        }
        if (level == Level.EXCLUSIVE && target == Level.SHARED) {
            state.range.release();
            state.range = null;
            restoreShared();
        }
        if (target == Level.NONE && state.readers == 1) {
            state.range.release();
            state.range = null;
        }
        if (state.reservedOwner == this) {
            state.reserved.release();
            state.reserved = null;
            state.reservedOwner = null;
        }
        if (state.pendingOwner == this) {
            state.pending.release();
            state.pending = null;
            state.pendingOwner = null;
        }
        if (target == Level.NONE) {
            state.readers--;
        }
        level = target;
    }

    /** Checks for a writer using a shared reserved-byte probe, including on RO handles. */
    public synchronized boolean checkReservedLock() throws IOException {
        state.mutex.lock();
        try {
            checkOpen();
            state.checkHealthy();
            if (state.reservedOwner != null || state.pendingOwner != null) {
                return true;
            }
            try {
                // SQLite 3.53.4 os_win.c winCheckReservedLock uses this shared
                // probe. Unlike an exclusive probe, it works on a read-only fd.
                state.reserved = state.tryLock(state.readable(), RESERVED_BYTE, 1, true);
                if (state.reserved == null) {
                    return true;
                }
                state.reserved.release();
                state.reserved = null;
                return false;
            } catch (IOException failure) {
                throw state.fail(failure);
            }
        } finally {
            state.mutex.unlock();
        }
    }

    /** Returns the local level; throws IllegalStateException after detected lock loss. */
    public synchronized Level level() {
        if (closed) {
            return Level.NONE;
        }
        try {
            checkHealthy();
        } catch (IOException failure) {
            throw new IllegalStateException("File lock state is no longer usable", failure);
        }
        return level;
    }

    /** Closes this logical handle; actual descriptors close only after the last handle. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        synchronized (FILES) {
            state.mutex.lock();
            try {
                IOException failure = null;
                try {
                    state.checkHealthy();
                    unlockTo(Level.NONE);
                } catch (IOException error) {
                    failure = state.fail(error);
                }
                closed = true;
                level = Level.NONE;
                if (--state.handles == 0) {
                    try {
                        state.original.close();
                    } catch (IOException error) {
                        failure = append(failure, error);
                    }
                    if (state.writable != null && state.writable != state.original) {
                        try {
                            state.writable.close();
                        } catch (IOException error) {
                            failure = append(failure, error);
                        }
                    }
                    FILES.remove(state.key);
                }
                if (failure != null) {
                    throw failure;
                }
            } finally {
                state.mutex.unlock();
            }
        }
    }

    private static IOException append(IOException primary, IOException additional) {
        if (primary == null) {
            return additional;
        }
        primary.addSuppressed(additional);
        return primary;
    }

    private static final class State {
        private final Object key;
        private final Path path;
        private final FileChannel original;
        private final ReentrantLock mutex = new ReentrantLock();
        private FileChannel writable;
        private int handles;
        private int readers;
        private RollbackFile reservedOwner;
        private RollbackFile pendingOwner;
        private FileLock range;
        private FileLock reserved;
        private FileLock pending;
        private IOException failure;

        private State(Object key, Path path, FileChannel channel, boolean writable) {
            this.key = key;
            this.path = path;
            this.original = channel;
            this.writable = writable ? channel : null;
        }

        private FileChannel readable() {
            return writable == null ? original : writable;
        }

        private void checkHealthy() throws IOException {
            if (failure != null) {
                throw new IOException("File state failed; close all handles before reopening", failure);
            }
            if (!original.isOpen() || (writable != null && !writable.isOpen())
                    || (range != null && !range.isValid())
                    || (reserved != null && !reserved.isValid())
                    || (pending != null && !pending.isValid())) {
                throw fail(new IOException("Shared channel or OS lock was invalidated"));
            }
        }

        private IOException fail(IOException error) {
            if (failure == null) {
                failure = error;
            }
            return error;
        }

        private FileLock tryLock(FileChannel channel, long offset, long size, boolean shared)
                throws IOException {
            FileLock lock;
            try {
                lock = channel.tryLock(offset, size, shared);
            } catch (OverlappingFileLockException uncoordinated) {
                throw new IOException("Uncoordinated same-process file lock", uncoordinated);
            }
            if (lock != null && shared && !lock.isShared()) {
                IOException error = new IOException("Filesystem does not provide real shared locks");
                try {
                    lock.release();
                } catch (IOException releaseFailure) {
                    error.addSuppressed(releaseFailure);
                }
                throw error;
            }
            return lock;
        }
    }
}
