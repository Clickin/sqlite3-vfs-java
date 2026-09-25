package io.github.clickin.sqlitevfs;

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static io.github.clickin.sqlitevfs.SqliteCodes.*;

/** SQLite 3.53.4 SHM nodes: one channel, DMS lock and mapping owner per file identity. */
final class SharedMemoryFile {
    static final int REGION_SIZE = 32768;
    private static final int LOCK_BASE = 120;
    private static final int DMS = 128;
    // ponytail: only attachment/final-detachment scan this registry; split by
    // file key if the number of simultaneously open WAL databases warrants it.
    private static final Map<Path, Node> NODES = new HashMap<>();

    static boolean supported() { return MappedRegion.supported(); }
    static String diagnostic() { return MappedRegion.diagnostic(); }

    static String qualifiedPlatform() {
        String os = System.getProperty("os.name", "");
        if (!(os.startsWith("Windows") || os.equals("Linux") || os.equals("Mac OS X"))) {
            throw new UnsupportedOperationException("Unqualified operating system: " + os);
        }
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new UnsupportedOperationException("Wasm WAL requires little-endian native SHM");
        }
        return os + "/" + System.getProperty("os.arch", "") + " Java " + Runtime.version()
                + ": FileChannel MAP_SHARED; native little-endian; ";
    }

    static final class Failure extends IOException {
        final int code;
        Failure(int code, String message) { super(message); this.code = code; }
        Failure(int code, String message, Throwable cause) { super(message, cause); this.code = code; }
    }

    private final Node node;
    private int sharedMask;
    private int exclusiveMask;
    private boolean detached;

    private SharedMemoryFile(Node node) {
        this.node = node;
        node.references++;
    }

    static SharedMemoryFile open(Path database, RollbackFile.ChannelOpener opener, FileSystemOps fs)
            throws Failure {
        if (!supported()) throw new Failure(SQLITE_IOERR_SHMMAP, diagnostic());
        Path path = database.resolveSibling(database.getFileName() + "-shm");
        synchronized (NODES) {
            try {
                Node existing = NODES.get(path);
                if (existing == null) {
                    try {
                        var attributes = fs.attributes(path, LinkOption.NOFOLLOW_LINKS);
                        if (!attributes.isRegularFile()) throw new IOException("SHM must be a regular, non-symlink file");
                        for (Node candidate : NODES.values()) {
                            if (fs.sameFile(path, candidate.path)) {
                                existing = candidate;
                                break;
                            }
                        }
                    } catch (NoSuchFileException missing) {
                        // A new sidecar is created below, never a process-private substitute.
                    }
                }
                if (existing != null) {
                    synchronized (existing) {
                        existing.healthy();
                        return new SharedMemoryFile(existing);
                    }
                }
                FileChannel channel;
                boolean readOnly = false;
                try {
                    channel = opener.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE);
                } catch (IOException writableFailure) {
                    try {
                        channel = opener.open(path, StandardOpenOption.READ);
                        readOnly = true;
                    } catch (IOException readFailure) {
                        writableFailure.addSuppressed(readFailure);
                        throw writableFailure;
                    }
                }
                Node created = new Node(path, channel, readOnly, fs);
                NODES.put(path, created);
                return new SharedMemoryFile(created);
            } catch (Failure failure) {
                throw failure;
            } catch (IOException | RuntimeException failure) {
                throw new Failure(SQLITE_IOERR_SHMOPEN, "Opening SQLite SHM " + path, failure);
            }
        }
    }

    NioVfs.ShmResult map(int page, int size, boolean extend) throws Failure {
        if (page < 0 || size != REGION_SIZE) {
            throw new Failure(SQLITE_MISUSE, "SHM requires nonnegative pages of 32768 bytes");
        }
        synchronized (node) {
            attached();
            node.healthy();
            int rc = node.initialize();
            if (rc != SQLITE_OK) return new NioVfs.ShmResult(rc, null);
            MappedRegion mapping = node.regions.get(page);
            if (mapping == null) {
                long required = ((long) page + 1) * REGION_SIZE;
                try {
                    long current = node.channel.size();
                    if (current < required) {
                        if (!extend || node.readOnly) {
                            node.finishInitialization();
                            return new NioVfs.ShmResult(node.readOnly ? SQLITE_READONLY : SQLITE_OK, null);
                        }
                        // os_unix.c touches each new 4096-byte page to allocate backing
                        // before mmap, reducing later SIGBUS exposure on full devices.
                        ByteBuffer zero = node.extensionByte;
                        for (long offset = (current / 4096 + 1) * 4096 - 1;
                             offset < required; offset += 4096) {
                            zero.clear();
                            if (node.channel.write(zero, offset) != 1) {
                                throw new IOException("No progress extending SQLite SHM");
                            }
                        }
                    }
                } catch (IOException | RuntimeException failure) {
                    throw node.poison(SQLITE_IOERR_SHMSIZE, "Sizing SQLite SHM", failure);
                }
                try {
                    mapping = new MappedRegion(node.channel, node.readOnly ? FileChannel.MapMode.READ_ONLY
                            : FileChannel.MapMode.READ_WRITE, (long) page * REGION_SIZE, REGION_SIZE);
                    node.regions.put(page, mapping);
                } catch (IOException | RuntimeException failure) {
                    throw node.poison(SQLITE_IOERR_SHMMAP, "Mapping SQLite SHM", failure);
                }
            }
            node.finishInitialization();
            return new NioVfs.ShmResult(node.readOnly ? SQLITE_READONLY : SQLITE_OK,
                    mapping.buffer().duplicate().order(ByteOrder.nativeOrder()));
        }
    }

    int lock(int offset, int count, int flags) throws Failure {
        boolean shared = (flags & SQLITE_SHM_SHARED) != 0;
        boolean unlock = (flags & SQLITE_SHM_UNLOCK) != 0;
        if (offset < 0 || count < 1 || offset > 8 - count || (shared && count != 1)
                || (flags != (SQLITE_SHM_LOCK | SQLITE_SHM_SHARED)
                && flags != (SQLITE_SHM_LOCK | SQLITE_SHM_EXCLUSIVE)
                && flags != (SQLITE_SHM_UNLOCK | SQLITE_SHM_SHARED)
                && flags != (SQLITE_SHM_UNLOCK | SQLITE_SHM_EXCLUSIVE))) {
            throw new Failure(SQLITE_MISUSE, "Invalid SQLite SHM lock range or flags");
        }
        synchronized (node) {
            attached();
            node.healthy();
            if (node.dms == null) throw new Failure(SQLITE_IOERR_SHMLOCK, "SHM is not initialized");
            int mask = ((1 << count) - 1) << offset;
            if (unlock) {
                int owned = mask & (sharedMask | exclusiveMask);
                if ((owned & (shared ? exclusiveMask : sharedMask)) != 0) {
                    throw new Failure(SQLITE_MISUSE, "SHM unlock mode differs from the held lock");
                }
                // SQLite unixShmLock permits empty or partially owned unlock ranges.
                release(owned);
                return SQLITE_OK;
            }
            if (node.readOnly && !shared) return SQLITE_READONLY_CANTLOCK;
            for (int i = offset; i < offset + count; i++) {
                if (shared ? node.owners[i] != null
                        : node.sharedHolders[i] != 0 || (node.owners[i] != null && node.owners[i] != this)) {
                    return SQLITE_BUSY;
                }
            }
            int acquired = 0;
            try {
                for (int i = offset; i < offset + count; i++) {
                    int bit = 1 << i;
                    if (((shared ? sharedMask : exclusiveMask) & bit) != 0) continue;
                    if (node.locks[i] == null) {
                        FileLock physical = node.tryLock(LOCK_BASE + i, shared);
                        if (physical == null) {
                            release(acquired);
                            return SQLITE_BUSY;
                        }
                        node.locks[i] = physical;
                    }
                    if (shared) {
                        node.sharedHolders[i]++;
                        sharedMask |= bit;
                    } else {
                        node.owners[i] = this;
                        exclusiveMask |= bit;
                    }
                    acquired |= bit;
                }
                VarHandle.fullFence();
                return SQLITE_OK;
            } catch (Failure failure) {
                try {
                    release(acquired);
                } catch (Failure rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    /** All guest aliases must be detached before this method is called. */
    void unmap(boolean delete) throws Failure {
        synchronized (NODES) {
            synchronized (node) {
                if (detached) return;
                release(sharedMask | exclusiveMask);
                if (node.references > 1) {
                    node.references--;
                    detached = true;
                    return;
                }
                // Retain each mapping until its synchronous close succeeds. A
                // cleanup failure leaves this connection/node owned and retryable.
                Iterator<MappedRegion> mappings = node.regions.values().iterator();
                while (mappings.hasNext()) {
                    try {
                        mappings.next().close();
                        mappings.remove();
                    } catch (Throwable failure) {
                        throw node.poison(SQLITE_IOERR_SHMMAP, "Unmapping SQLite SHM", failure);
                    }
                }
                try {
                    if (node.initializationGate != null) {
                        node.initializationGate.release();
                        node.initializationGate = null;
                    }
                    if (node.unexpectedLock != null) {
                        node.unexpectedLock.release();
                        node.unexpectedLock = null;
                    }
                    if (node.dms != null) {
                        node.dms.release();
                        node.dms = null;
                    }
                    if (delete && !node.readOnly) {
                        // Never unlink an index still attached by a native process.
                        // SQLite callers additionally serialize deletion with the
                        // main-database EXCLUSIVE lock (wal.c sqlite3WalClose).
                        node.dms = node.tryLock(DMS, false);
                        if (node.dms != null) {
                            try {
                                node.fs.delete(node.path);
                            } catch (NoSuchFileException alreadyGone) {
                                // The requested delete postcondition already holds.
                            }
                        }
                    }
                    node.channel.close();
                } catch (IOException | RuntimeException failure) {
                    // A failed close can already report isOpen()==false while its
                    // native outcome is unknown: quarantine rather than reopen it.
                    node.closeUncertain |= !node.channel.isOpen();
                    throw node.poison(SQLITE_IOERR_SHMOPEN, "Closing SQLite SHM", failure);
                }
                if (node.closeUncertain) {
                    throw node.poison(SQLITE_IOERR_SHMOPEN, "Earlier SHM close has uncertain native outcome", null);
                }
                node.dms = null;
                node.references = 0;
                detached = true;
                NODES.remove(node.path);
            }
        }
    }

    private void attached() throws Failure {
        if (detached) throw new Failure(SQLITE_IOERR_SHMOPEN, "SHM connection is detached");
    }

    private void release(int mask) throws Failure {
        VarHandle.fullFence();
        for (int i = 0; i < 8; i++) {
            int bit = 1 << i;
            if ((mask & bit) == 0) continue;
            boolean shared = (sharedMask & bit) != 0;
            if ((shared && node.sharedHolders[i] == 1) || (exclusiveMask & bit) != 0) {
                try {
                    node.locks[i].release();
                    node.locks[i] = null;
                } catch (IOException | RuntimeException failure) {
                    throw node.poison(SQLITE_IOERR_SHMLOCK, "Releasing SQLite SHM lock", failure);
                }
            }
            if (shared) {
                node.sharedHolders[i]--;
                sharedMask &= ~bit;
            } else {
                node.owners[i] = null;
                exclusiveMask &= ~bit;
            }
        }
    }

    private static final class Node {
        final Path path;
        final FileChannel channel;
        final boolean readOnly;
        final FileSystemOps fs;
        final Map<Integer, MappedRegion> regions = new HashMap<>();
        final FileLock[] locks = new FileLock[8];
        final int[] sharedHolders = new int[8];
        final SharedMemoryFile[] owners = new SharedMemoryFile[8];
        final ByteBuffer extensionByte = ByteBuffer.allocate(1);
        final ByteBuffer invalidHeader = ByteBuffer.allocate(48);
        int references;
        FileLock dms;
        FileLock unexpectedLock;
        FileLock initializationGate;
        Failure failure;
        boolean closeUncertain;

        Node(Path path, FileChannel channel, boolean readOnly, FileSystemOps fs) {
            this.path = path;
            this.channel = channel;
            this.readOnly = readOnly;
            this.fs = fs;
        }

        int initialize() throws Failure {
            if (dms != null) return SQLITE_OK;
            // FileChannel cannot request an exclusive lock on a read-only
            // channel or perform Unix F_GETLK. Never trust a possibly stale
            // readonly index without proving a live initializer.
            if (readOnly) return SQLITE_READONLY_CANTINIT;
            try {
                dms = tryLock(DMS, false);
                if (dms != null) {
                    channel.truncate(0);
                    dms.release();
                    dms = null;
                } else {
                    // NIO has no F_GETLK: a failed exclusive DMS probe cannot
                    // distinguish a live shared holder from a crashing initializer.
                    // Exclude EVERY WAL transaction before invalidating the index.
                    initializationGate = tryLock(LOCK_BASE, 8, false);
                    if (initializationGate == null) return SQLITE_BUSY;

                    // Crucially invalidate BEFORE publishing our shared DMS.
                    // Otherwise a crash between DMS publication and invalidation
                    // lets a native opener trust the stale index. Positional I/O
                    // (not mmap) stays safe if a native initializer holding DMS
                    // concurrently truncates: both operations only invalidate.
                    // The caller's main-db SHARED lock prevents normal unlink.
                    invalidateHeader(48);
                    VarHandle.fullFence();
                    invalidateHeader(0);
                    VarHandle.fullFence();
                }
                dms = tryLock(DMS, true);
                if (dms == null) finishInitialization();
                return dms == null ? SQLITE_BUSY : SQLITE_OK;
            } catch (IOException | RuntimeException failure) {
                throw poison(SQLITE_IOERR_SHMOPEN, "Initializing SQLite SHM DMS", failure);
            }
        }

        void finishInitialization() throws Failure {
            if (initializationGate != null) {
                try {
                    VarHandle.fullFence();
                    initializationGate.release();
                    initializationGate = null;
                } catch (IOException | RuntimeException failure) {
                    throw poison(SQLITE_IOERR_SHMLOCK, "Releasing WAL initialization gate", failure);
                }
            }
        }

        private void invalidateHeader(long offset) throws IOException {
            invalidHeader.clear();
            while (invalidHeader.hasRemaining()) {
                int count = channel.write(invalidHeader, offset + invalidHeader.position());
                if (count == 0) throw new IOException("No progress invalidating SQLite WAL-index header");
            }
        }

        FileLock tryLock(long offset, boolean shared) throws Failure {
            return tryLock(offset, 1, shared);
        }

        private FileLock tryLock(long offset, long count, boolean shared) throws Failure {
            try {
                FileLock lock = channel.tryLock(offset, count, shared);
                if (lock != null && shared && !lock.isShared()) {
                    try {
                        lock.release();
                    } catch (IOException | RuntimeException releaseFailure) {
                        unexpectedLock = lock;
                        throw poison(SQLITE_IOERR_SHMLOCK, "Provider promoted shared SHM lock; release failed", releaseFailure);
                    }
                    throw poison(SQLITE_IOERR_SHMLOCK, "Provider does not implement shared SHM locks", null);
                }
                return lock;
            } catch (OverlappingFileLockException conflict) {
                // A different classloader or uncoordinated channel is unsafe on
                // POSIX even if Java still considers its FileLock valid.
                throw poison(SQLITE_IOERR_SHMLOCK, "Uncoordinated overlapping JVM SHM lock", conflict);
            } catch (IOException | RuntimeException failure) {
                throw poison(SQLITE_IOERR_SHMLOCK, "Acquiring SQLite SHM lock", failure);
            }
        }

        void healthy() throws Failure {
            if (failure != null) throw failure;
            if (!channel.isOpen() || (dms != null && !dms.isValid())) {
                throw poison(SQLITE_IOERR_SHMLOCK, "SHM channel or DMS lock was lost", null);
            }
            for (FileLock lock : locks) {
                if (lock != null && !lock.isValid()) {
                    throw poison(SQLITE_IOERR_SHMLOCK, "SHM lock was lost", null);
                }
            }
        }

        Failure poison(int code, String message, Throwable cause) {
            Failure current = new Failure(code, message, cause);
            if (failure == null) failure = current;
            return current;
        }
    }

}
