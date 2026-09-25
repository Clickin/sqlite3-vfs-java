# sqlite3-vfs-java

[![Build and tests](https://github.com/Clickin/sqlite3-vfs-java/actions/workflows/conformance.yml/badge.svg)](https://github.com/Clickin/sqlite3-vfs-java/actions/workflows/conformance.yml)

An experimental Java implementation of SQLite's [Virtual File System interface](https://sqlite.org/vfs.html), providing direct access to database files through Java NIO.

It implements the file I/O, locking and shared memory that SQLite needs for rollback journals and WAL. A JVM-based SQLite engine can use it to work with ordinary database files, including files accessed by native SQLite in another process.

The repository also includes a working JDBC integration based on [sqlite4j](https://github.com/roastedroot/sqlite4j). It connects SQLite compiled to JVM bytecode to this VFS, so database changes go directly to host files rather than an in-memory filesystem that must be exported separately.

## What it supports

- Reading and writing SQLite database files, rollback journals and WAL files.
- Coordinating connections within the JVM and file locks across processes.
- File-backed WAL shared memory compatible with native SQLite.
- Transactions, checkpointing and recovery through the included SQLite engine.
- Java 11 and Java 25 builds, tested on Linux, macOS and Windows.

The VFS itself depends only on the JDK. It does not include a SQL engine or require an application-provided native library. The optional JDBC integration uses [Endive](https://github.com/bytecodealliance/endive) to run compiled SQLite as JVM bytecode; Wasm remains part of its build pipeline and runtime metadata.

## Build

The project is not yet published to Maven Central. Use the Maven wrapper to build from source, with `JAVA_HOME` set to the selected JDK.

### Java 11

```sh
./mvnw install
```

### Java 25

```sh
./mvnw -f pom-java25.xml install
```

Both builds share the same file and locking implementation. The Java 11 build uses `Unsafe.invokeCleaner` to release shared-memory mappings and requires `jdk.unsupported`. The Java 25 build uses the standard `Arena` API instead.

| Target | VFS artifact | JDBC artifact |
|---|---|---|
| Java 11 | `sqlite3-vfs` | `sqlite3-vfs-jdbc` |
| Java 25 | `sqlite3-vfs-java25` | `sqlite3-vfs-jdbc-java25` |

All artifacts use group `io.github.clickin` and version `0.1.0-SNAPSHOT`. Choose one variant; they provide the same Java classes and must not be used together on the classpath.

## Try the JDBC integration

First build and install the VFS above. Then compile SQLite and build the JDBC module:

```sh
# Downloads the pinned SQLite sources and WASI SDK; requires Linux or macOS.
bash integration-engine/native/build.sh

# Use the descriptor matching your VFS build and JAVA_HOME.
./mvnw -f integration-engine/pom.xml install          # Java 11
# ./mvnw -f integration-engine/pom-java25.xml install # Java 25
```

On Windows, use `mvnw.cmd`. The engine can be built there using the `sqlite-vfs.wasm` file from the `sqlite-engine-input` artifact of a [CI run](https://github.com/Clickin/sqlite3-vfs-java/actions/workflows/conformance.yml), placed in `integration-engine/target/`.

For example, after installing the Java 11 artifacts locally, add this dependency to your application:

```xml
<dependency>
  <groupId>io.github.clickin</groupId>
  <artifactId>sqlite3-vfs-jdbc</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For Java 25, use `sqlite3-vfs-jdbc-java25` instead. The matching VFS dependency is included transitively.

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class Example {
    public static void main(String[] args) throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:example.db");
             Statement sql = db.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL");
            sql.execute("CREATE TABLE IF NOT EXISTS notes (text TEXT NOT NULL)");
            sql.execute("INSERT INTO notes VALUES ('Stored in a real database file')");
        }
    }
}
```

This creates a host database file that native SQLite can also open. Do not add xerial's SQLite JDBC driver to the same application classpath: both drivers recognize `jdbc:sqlite:` URLs.

## Current limitations

This is an experimental implementation, not a drop-in replacement for every SQLite VFS or JDBC driver.

- **Local filesystems only.** Network filesystems and concurrent rename, unlink or replacement of open database files are outside the supported scope.
- **Windows directory sync is unavailable through the current Java implementation.** Explicit requests return an error; ordinary file sync is supported. Full power-loss durability is not established.
- **The first Java WAL attachment may return `SQLITE_BUSY_RECOVERY`** while a native transaction is active. Retry after that transaction ends. WAL initialization requires writable shared-memory sidecar files.
- **Within one JVM, access to the same database must use the same VFS coordinator/classloader.** Unrelated raw file opens and closes can interfere with POSIX locks.
- **Do not interrupt file-I/O threads to cancel work.** This can close a shared channel. The JDBC integration provides cooperative SQL cancellation.
- A descriptor-close failure with an uncertain outcome requires a JVM restart before that file can be reopened through the VFS.
- Native extension loading, shared-cache mode and database-page mmap (`xFetch`) are not supported.

## Testing and implementation details

CI runs both Java variants on Linux, macOS and Windows. It exercises the VFS against native SQLite in separate processes, runs SQL and crash-recovery tests, and checks concurrent reads, commits, checkpointing and repeated connection open/close under load.

See the [verification results and detailed limitations](IMPLEMENTATION-RESULTS.md) for the test matrix, VM power-cut experiments and durability qualifications. The [pinned SQLite reference](reference-sqlite.properties) and [third-party notices](integration-engine/native/THIRD-PARTY.txt) document the engine sources and dependencies.
