package io.github.clickin.sqlitevfs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Package-private, real-backed metadata boundary for deterministic failure injection. */
class FileSystemOps {
    static final FileSystemOps SYSTEM = new FileSystemOps();

    BasicFileAttributes attributes(Path path, LinkOption... options) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, options);
    }

    Path realPath(Path path) throws IOException {
        return path.toRealPath();
    }

    Path readSymbolicLink(Path path) throws IOException {
        return Files.readSymbolicLink(path);
    }

    boolean sameFile(Path first, Path second) throws IOException {
        return Files.isSameFile(first, second);
    }

    void checkAccess(Path path, AccessMode... modes) throws IOException {
        path.getFileSystem().provider().checkAccess(path, modes);
    }

    Path createTempFile() throws IOException {
        // The JDK creates atomically, with owner-only permissions on POSIX providers.
        return Files.createTempFile("etilqs_", ".tmp");
    }

    void delete(Path path) throws IOException {
        Files.delete(path);
    }

    void syncDirectory(Path directory) throws IOException {
        // Provider-specific, not a portable Java directory-fsync guarantee.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
