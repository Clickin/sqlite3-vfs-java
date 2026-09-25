# PLAN — Pure Java `sqlite3_vfs` from a Fresh Repository

## Mission

Create a fresh Java 25 repository whose only primary goal is to implement SQLite's operating-system interface semantics in pure Java.

The repository must begin **without**:

- sqlite4j source code;
- xerial JDBC source code;
- Endive;
- Chicory;
- WebAssembly build steps;
- JNI;
- FFM;
- native runtime libraries.

The first deliverable is not a JDBC driver and not a runnable SQLite engine.

The first deliverable is a production-grade, independently testable Java implementation of the semantics represented by:

- `sqlite3_vfs`;
- `sqlite3_file`;
- `sqlite3_io_methods`.

The implementation must be suitable for attaching later to a SQLite engine that runs as JVM bytecode.

The key acceptance criterion is interoperability with ordinary native SQLite filesystem and locking behavior, not merely internal self-consistency.

---

# 0. Important architectural boundary

`sqlite3_vfs` is the OS abstraction **used by the SQLite engine**. A VFS alone is not a SQL engine.

Therefore distinguish these goals:

## Goal A — this repository

```text
SQLite VFS semantics
        |
        v
pure Java
        |
        +-- Java NIO file I/O
        +-- SQLite-compatible byte-range locking
        +-- durability/sync behavior
        +-- temp files
        +-- time/randomness
        +-- later WAL shared memory
```

This can and should be implemented without Wasm or an embedded SQLite engine.

## Goal B — later integration

```text
SQLite engine executing as JVM bytecode
        |
        v
Java VFS from Goal A
        |
        v
host filesystem
```

How the SQLite engine becomes JVM bytecode is a later problem.

Possible future options include:

1. SQLite C -> Wasm -> ahead-of-time Java bytecode, with no Wasm artifact required at runtime;
2. a direct C/LLVM -> JVM bytecode path if a sufficiently correct toolchain exists;
3. another generated/transpiled SQLite engine representation.

Do **not** assume that implementing the VFS removes the need for an SQLite engine.

Likewise, "no Wasm at runtime" and "no Wasm anywhere in the build pipeline" are different goals.

This repository must not couple itself to either choice.

---

# 1. Project principles

1. **JDK 25 minimum.**
2. **Pure Java runtime.**
3. **No hidden worker threads.**
4. **No internal executor used to hide blocking calls.**
5. **Virtual-thread-friendly blocking strategy where Java gives us control.**
6. **Native SQLite interoperability is mandatory.**
7. **Cross-process behavior matters.**
8. **Linux, macOS, and Windows are first-class targets.**
9. **Rollback-journal correctness comes before WAL.**
10. **Conservative behavior is preferred to optimistic filesystem claims.**
11. **Do not advertise durability guarantees that Java APIs cannot prove.**
12. **Do not continue past a portability or safety gate by inventing a fake implementation.**

---

# 2. Fresh repository bootstrap

Initialize a new repository.

Suggested working name:

```text
sqlite-vfs-java
```

The name is not part of the API and may change later.

Record:

```bash
git init
git status
java -version
```

Use JDK 25.

Choose a conventional Java build with a wrapper committed to the repository.

Prefer a small multi-module build only where modules provide real isolation.

Suggested initial layout:

```text
/
├── README.md
├── LICENSE
├── PLAN.md
├── pom.xml or build.gradle(.kts)
├── vfs-core/
├── vfs-nio/
├── vfs-testkit/
├── vfs-jcstress/
└── integration-native-oracle/
```

Do not add an engine module yet.

Suggested responsibilities:

```text
vfs-core
  API, SQLite constants, state models, error mapping,
  no filesystem-provider-specific code

vfs-nio
  java.nio implementation
  file handles
  lock coordinator
  platform lock backends
  sync/durability behavior

vfs-testkit
  reusable state-machine tests
  fault injection
  cross-process test protocol
  fixtures

vfs-jcstress
  same-JVM concurrency verification of lock state/coordinator

integration-native-oracle
  test-only dependency on xerial sqlite-jdbc / native sqlite
  subprocess interoperability tests
```

`integration-native-oracle` is allowed to use JNI because it is an **external reference implementation used by tests**, not part of the produced VFS runtime.

No production module may depend on it.

---

# 3. Reference version

At repository initialization, record the exact SQLite version against which constants and behavior are being implemented.

As of this plan, the current xerial SQLite JDBC release is:

```text
org.xerial:sqlite-jdbc:3.53.4.0
SQLite: 3.53.4
```

Re-check this when work actually begins.

Do not couple the VFS public Java API to an xerial version.

Keep a machine-readable compatibility file, for example:

```text
reference-sqlite.properties
```

containing:

```properties
sqlite.version=3.53.4
sqlite.source-id=...
xerial.version=3.53.4.0
```

When upgrading the reference SQLite version, run the complete conformance suite.

---

# 4. Model the SQLite VFS API in Java

Do not model the C structs byte-for-byte in the core API.

Model their semantics.

A possible shape:

```java
public interface SqliteVfs {
    OpenResult open(SqlitePath path, OpenFlags flags);
    SqliteResult delete(SqlitePath path, boolean syncDirectory);
    AccessResult access(SqlitePath path, AccessMode mode);
    PathResult fullPathname(SqlitePath path);

    int randomness(ByteBuffer target);
    SleepResult sleep(Duration duration);
    long currentTimeMillisJulian();
}
```

and:

```java
public interface SqliteFile extends AutoCloseable {
    IoResult read(ByteBuffer target, long offset);
    IoResult write(ByteBuffer source, long offset);
    IoResult truncate(long size);
    IoResult sync(SyncMode mode);
    FileSizeResult fileSize();

    LockResult lock(SqliteLockLevel level);
    LockResult unlock(SqliteLockLevel level);
    ReservedLockResult checkReservedLock();

    FileControlResult fileControl(...);
    int sectorSize();
    DeviceCharacteristics deviceCharacteristics();

    SharedMemoryResult shmMap(...);
    LockResult shmLock(...);
    void shmBarrier();
    IoResult shmUnmap(...);

    FetchResult fetch(...);
    IoResult unfetch(...);
}
```

This exact API is not mandatory.

The requirements are:

- exact SQLite semantics can be represented;
- no C ABI assumptions leak into filesystem logic;
- a future engine adapter can map integer/pointer handles to Java objects;
- return codes remain explicit;
- exception translation is centralized.

---

# 5. SQLite constants

Create explicit Java definitions for:

- primary result codes;
- extended I/O result codes used by a VFS;
- open flags;
- access flags;
- lock levels;
- sync flags;
- file-control opcodes needed by the implementation;
- device-characteristic flags;
- shared-memory lock flags.

Do not casually duplicate magic integers throughout the implementation.

Where practical, write a verification test that compares Java constants with the selected `sqlite3.h` reference source or xerial-exposed values.

---

# 6. Phase 1 — File I/O semantics before locking

Implement and test the basic `sqlite3_io_methods` file behavior first.

## `xOpen`

Support at minimum:

- `SQLITE_OPEN_READONLY`;
- `SQLITE_OPEN_READWRITE`;
- `SQLITE_OPEN_CREATE`;
- `SQLITE_OPEN_DELETEONCLOSE`;
- object type flags:
  - main DB;
  - main journal;
  - temp DB;
  - temp journal;
  - transient DB;
  - subjournal;
  - super-journal;
  - WAL.

Requirements:

- correct read-only fallback behavior;
- no accidental truncate on open;
- deterministic cleanup after failed open;
- temp filename generation when SQLite supplies no name;
- delete-on-close correctness;
- output flags accurately report what was actually opened.

## `xRead`

SQLite short-read behavior is special.

When EOF is reached before the requested byte count:

1. copy all bytes that exist;
2. zero-fill the unread suffix of the requested buffer;
3. return `SQLITE_IOERR_SHORT_READ`.

This must have explicit tests.

Do not return an ordinary Java EOF indication.

## `xWrite`

Requirements:

- write the complete requested byte range or return an I/O error;
- handle partial `FileChannel.write()` progress correctly;
- do not assume one write call writes everything;
- preserve positional-write semantics;
- distinguish read-only failures.

## `xTruncate`

Test:

- shrinking;
- extending where filesystem semantics permit;
- truncate to zero;
- invalid sizes;
- read-only files.

## `xFileSize`

Must return a `long`.

Test sparse/large files beyond 2 GiB without allocating equivalent physical storage where supported.

## `xClose`

Must be idempotence-safe at the Java wrapper layer even though SQLite should have a defined lifecycle.

Verify:

- channel closed;
- locks released;
- coordinator references released;
- delete-on-close honored;
- exceptions do not leak resources.

---

# 7. Phase 2 — Path semantics and filesystem boundary

Implement:

- `xDelete`;
- `xAccess`;
- `xFullPathname`.

Path identity is important because locking must coordinate aliases of the same file.

Investigate and define a `FileIdentity`.

Candidate inputs:

```text
Path.toRealPath()
BasicFileAttributes.fileKey()
FileStore identity
platform-normalized path
```

Requirements:

- symlink aliases;
- relative paths;
- `..`;
- Windows case-insensitive paths;
- Windows drive letters;
- UNC paths if supported;
- hard links;
- file replacement;
- paths not yet existing when `CREATE` is requested.

Do not key same-JVM locks only by the raw input string.

Document unresolved hard-link/file-replacement edge cases.

---

# 8. Phase 3 — Exact rollback-journal lock protocol

This is the central phase.

Implement SQLite-compatible locking, not a custom mutex scheme.

The lock state model is:

```text
NONE
  |
  v
SHARED
  |
  v
RESERVED
  |
  v
PENDING
  |
  v
EXCLUSIVE
```

SQLite's standard lock-byte region is based on:

```text
PENDING_BYTE  = 0x40000000
RESERVED_BYTE = PENDING_BYTE + 1
SHARED_FIRST  = PENDING_BYTE + 2
SHARED_SIZE   = 510
```

Treat the SQLite source for the selected reference version as authoritative.

Do not infer the algorithm only from documentation.

---

# 9. Platform-specific locking backends

Do not force one implementation onto all operating systems merely because `FileChannel` has one API.

Create an internal abstraction:

```java
interface SqliteLockBackend {
    LockAttempt shared(...);
    LockAttempt reserved(...);
    LockAttempt pending(...);
    LockAttempt exclusive(...);
    void unlockTo(...);
}
```

Candidate implementations:

```text
PosixFileLockBackend
WindowsFileLockBackend
```

The backends may both use `FileChannel.tryLock()`, but they must be independently validated against each platform's native SQLite implementation.

## Critical rule

Never use an indefinitely blocking:

```java
FileChannel.lock(...)
```

inside `xLock`.

Prefer non-blocking:

```java
tryLock(...)
```

and return `SQLITE_BUSY` according to SQLite's expected behavior.

SQLite's upper layers and busy handler own retry/wait policy.

This also avoids hiding long native file-lock waits inside a Java virtual thread.

---

# 10. Same-JVM lock coordinator

Java NIO deliberately prevents overlapping file locks in one JVM and may throw:

```text
OverlappingFileLockException
```

before the operating system sees the lock request.

SQLite, however, may have multiple independent connections in the same process.

Implement a process-local coordinator.

Conceptually:

```text
FileIdentity
   |
   +-- open handles
   +-- shared holders
   +-- reserved holder
   +-- pending holder
   +-- exclusive holder
   +-- underlying OS FileLocks
```

The coordinator must:

- allow multiple local SHARED holders;
- prevent invalid local writer combinations;
- keep the OS-level lock visible to other processes;
- reference-count OS locks where appropriate;
- perform state transitions atomically;
- release OS locks only when the last corresponding JVM holder releases them;
- survive connection close in unusual orders;
- not deadlock when two local connections race.

Do not use `synchronized` indiscriminately over file I/O.

Prefer a small explicit state lock.

---

# 11. Lock state-machine specification

Before implementation, write the valid transitions as executable tests.

Examples:

```text
NONE -> SHARED            allowed
SHARED -> RESERVED        allowed when no RESERVED owner
RESERVED -> EXCLUSIVE     requires pending/exclusive acquisition
SHARED -> EXCLUSIVE       must follow SQLite algorithm
EXCLUSIVE -> SHARED       downgrade
EXCLUSIVE -> NONE         release all
RESERVED -> NONE          release reserved/shared as required
```

Test illegal requests and repeated requests.

Model the behavior independently from the Java NIO backend.

Use property/state-machine testing to generate operation sequences.

A failing sequence must be printed in reproducible form.

---

# 12. Phase 4 — Native SQLite locking interoperability

This is a hard acceptance gate.

Do not connect the Java VFS to an SQLite engine yet.

Instead validate the **file locking protocol itself**.

Use xerial's native SQLite in a separate JVM as the primary portable oracle.

The reference process must be a different JVM process.

## Java VFS -> native SQLite

1. Java VFS opens a DB file.
2. Java VFS acquires the equivalent of SQLite RESERVED / EXCLUSIVE state.
3. Separate xerial process attempts:

```sql
BEGIN IMMEDIATE;
```

Expected:

```text
SQLITE_BUSY / database is locked
```

as appropriate.

Release the Java lock.

Retry.

Expected:

```text
success
```

## Native SQLite -> Java VFS

1. Separate xerial process opens the file.
2. `BEGIN IMMEDIATE`.
3. Keep transaction open.
4. Java VFS attempts RESERVED/EXCLUSIVE acquisition.

Expected:

```text
busy
```

Release native transaction.

Retry.

Expected:

```text
success
```

## Reader/writer combinations

Test:

```text
native SHARED reader vs Java RESERVED writer
native reader vs Java EXCLUSIVE writer
Java SHARED readers vs native writer
multiple Java readers vs native writer
native writer vs multiple Java readers
```

## Same tests with roles reversed

Both directions are mandatory.

### Gate B

Do not proceed to engine integration until these tests pass on:

- Linux;
- macOS;
- Windows.

A platform may have a separate implementation if Java NIO maps differently there.

---

# 13. Test-only native oracle protocol

Do not make CI depend on brittle shell parsing.

Create a tiny Java subprocess program in `integration-native-oracle` using xerial JDBC.

It should support commands such as:

```text
hold-read-lock <db>
hold-immediate <db>
hold-exclusive <db>
try-immediate <db>
integrity-check <db>
checkpoint <db>
```

The parent test waits until the subprocess explicitly reports:

```text
READY
```

before attempting competing operations.

Use deterministic IPC:

- loopback socket;
- stdin/stdout line protocol;
- or named temporary control file with strict handshaking.

Do not use arbitrary sleeps as synchronization.

---

# 14. Phase 5 — concurrency verification inside the JVM

Add both ordinary stress tests and `jcstress`.

Target the coordinator, not SQL behavior.

## Race cases

At minimum:

- two simultaneous SHARED acquisitions;
- two simultaneous RESERVED acquisitions;
- SHARED acquisition while RESERVED exists;
- SHARED acquisition while PENDING exists;
- EXCLUSIVE promotion while readers exit;
- close racing with downgrade;
- last shared holder releasing;
- failed OS lock acquisition while local state changes;
- exception during OS unlock;
- repeated open/close of aliases of same path.

## Invariants

Examples:

```text
at most one RESERVED owner
at most one PENDING owner
at most one EXCLUSIVE owner
EXCLUSIVE implies no other SHARED owners
OS lock refcount never negative
closed handle owns no locks
local state and OS state do not diverge after normal operations
```

Run long randomized stress separately from deterministic CI tests.

---

# 15. Phase 6 — sync and durability semantics

This may expose a fundamental pure-Java portability limit.

Implement:

```text
xSync
xDelete(..., syncDir=true)
```

only after determining what Java 25 can actually guarantee on each target OS.

For normal file sync investigate:

```java
FileChannel.force(false)
FileChannel.force(true)
```

Map SQLite's sync flags conservatively.

Do not claim `FULL` semantics solely from the method name.

## Directory sync problem

SQLite may request synchronization of a containing directory after metadata-changing operations.

Java does not provide an obviously portable, specified directory-fsync API.

Experiment on:

- Linux;
- macOS;
- Windows.

Test whether a directory can be opened and forced through standard Java APIs.

Record:

```text
supported?
documented by Java API?
provider-specific?
what exception occurs?
```

### Gate C — durability claim

If strict native-equivalent directory sync cannot be implemented in pure Java on a platform:

- do not hide the result;
- keep the VFS usable with an explicitly weaker durability contract if safe;
- expose/document the limitation;
- do not claim bit-for-bit power-failure durability equivalence with native SQLite.

This does not necessarily block the whole project, but it blocks a stronger durability claim.

---

# 16. Fault-injectable filesystem layer

Do not write tests that require real disks to fail at convenient times.

Put a narrow file-operation layer under `vfs-nio`.

It must be possible in tests to fail the Nth:

- open;
- read;
- write;
- truncate;
- sync;
- close;
- delete;
- rename/move if used;
- lock;
- unlock.

Inject:

- partial read;
- partial write;
- `IOException`;
- access denied;
- no space;
- file disappeared;
- channel closed;
- sync failure.

Verify exact SQLite VFS-style result code mapping.

No unchecked Java I/O exception should accidentally cross the future SQLite engine boundary unless classified as an internal programming error.

---

# 17. Error mapping

Create a central mapper for Java filesystem failures to SQLite codes.

Prefer extended I/O codes when the reason is known.

Examples to investigate:

```text
SQLITE_IOERR_READ
SQLITE_IOERR_SHORT_READ
SQLITE_IOERR_WRITE
SQLITE_IOERR_FSYNC
SQLITE_IOERR_TRUNCATE
SQLITE_IOERR_FSTAT
SQLITE_IOERR_UNLOCK
SQLITE_IOERR_RDLOCK
SQLITE_IOERR_DELETE
SQLITE_IOERR_ACCESS
SQLITE_IOERR_CHECKRESERVEDLOCK
SQLITE_CANTOPEN
SQLITE_READONLY
SQLITE_FULL
SQLITE_BUSY
```

Do not collapse every `IOException` into `SQLITE_IOERR`.

Tests must verify mappings.

---

# 18. Phase 7 — remaining VFS methods

Implement the non-file methods.

## Randomness

`xRandomness`

Requirements:

- fill exactly the requested byte count;
- no unnecessary native dependency;
- deterministic injectable source for tests.

## Sleep

`xSleep`

Prefer a Virtual-Thread-friendly Java wait.

Do not busy-spin.

Test:

- zero duration;
- small duration;
- interruption behavior;
- reported elapsed microseconds according to SQLite expectations.

## Current time

Implement:

- Julian day double form if needed by adapter;
- integer millisecond Julian form for VFS v2/v3 semantics.

Test against known timestamps including:

- Unix epoch;
- leap day;
- UTC day boundaries.

Do not accidentally use local time.

## Dynamic library functions

A pure-Java SQLite runtime cannot load native SQLite extensions by default.

Implement safe unsupported semantics for:

```text
xDlOpen
xDlError
xDlSym
xDlClose
```

unless a later Java-extension model is created.

Do not silently attempt `System.load()`.

## `xGetLastError`

Define useful diagnostics without depending on mutable global state.

---

# 19. Conservative `sqlite3_io_methods` capabilities

Until proven otherwise:

## `xSectorSize`

Do not pretend Java knows the physical sector size if it does not.

Study what SQLite requires and what the built-in VFS returns as fallback.

Use a conservative value supported by evidence.

## `xDeviceCharacteristics`

Initially return no optimistic capabilities.

Do not claim:

- atomic writes;
- powersafe overwrite;
- safe append;
- undeletable-when-open;

unless tests and Java API semantics support them.

Conservative answers may reduce optimization but preserve correctness.

## `xFetch` / `xUnfetch`

Start unsupported if necessary.

A VFS can operate without mmap optimization.

Add mmap only as a later performance phase.

---

# 20. Phase 8 — VFS engine-adapter design, but still no SQLite engine dependency

After the Java semantics pass platform interoperability, define a very small adapter boundary suitable for a generated/transpiled SQLite engine.

Do not contaminate `vfs-core` with pointer arithmetic.

Recommended adapter model:

```text
engine integer handle
       |
       v
HandleTable<SqliteFile>
       |
       v
Java SqliteFile
```

Example conceptual imports:

```text
jvfs_open(...)
jvfs_close(handle)
jvfs_read(handle, address, amount, offset)
jvfs_write(handle, address, amount, offset)
jvfs_truncate(handle, size)
jvfs_sync(handle, flags)
jvfs_file_size(handle, outAddress)

jvfs_lock(handle, lockLevel)
jvfs_unlock(handle, lockLevel)
jvfs_check_reserved_lock(handle, outAddress)
```

Keep memory copying abstract:

```java
interface EngineMemory {
    void read(...);
    void write(...);
}
```

so a future engine can be:

- generated JVM bytecode;
- Wasm AOT;
- another memory representation.

No adapter should require JNI or FFM.

---

# 21. Phase 9 — rollback-journal integration gate

Only after the VFS independently passes native lock interoperability should another branch/repository integrate an actual SQLite engine.

The first integration target must use rollback journal mode.

Required SQL behavior:

```sql
PRAGMA journal_mode=DELETE;
PRAGMA synchronous=FULL;
```

Then test:

- create;
- reopen;
- read;
- write;
- transactions;
- rollback;
- hot-journal recovery;
- `VACUUM`;
- `ATTACH`;
- multiple connections;
- external native SQLite interoperability.

After every destructive/stress scenario:

```sql
PRAGMA integrity_check;
```

Run the same file through native xerial SQLite and the JVM engine.

---

# 22. Phase 10 — WAL as a separate project phase

Do not implement WAL until rollback-journal mode is trustworthy.

WAL requires VFS version-2 shared-memory methods:

```text
xShmMap
xShmLock
xShmBarrier
xShmUnmap
```

The standard SQLite WAL index and its locking protocol must be treated as the reference.

## Design requirement

Same-JVM-only shared memory is not enough if the project claims ordinary SQLite file interoperability.

If normal WAL is supported, this must work:

```text
JVM pure-Java SQLite process
        +
native sqlite3/xerial process
        |
        v
same database.db
same database.db-wal
same database.db-shm
```

with correct mutual exclusion and visibility.

## Separate acceptance gate

Cross-process WAL interoperability must pass on all supported OSes.

If Windows/macOS/Linux need distinct SHM implementations, keep them distinct.

Do not generalize prematurely.

---

# 23. Virtual Thread requirements

This repository uses Java 25 and should behave naturally on Virtual Threads.

The VFS must not create its own workers.

## Required test forms

Run VFS operations from:

- platform threads;
- one virtual thread;
- many virtual threads.

For lock contention:

- use non-blocking OS lock attempts;
- let SQLite/busy logic perform retries later;
- never park a platform worker hidden inside the VFS.

For `xSleep`:

- use Java scheduling primitives that work naturally with virtual threads.

For filesystem I/O:

- measure rather than assume.

## JFR

Run a dedicated Java 25 JFR stress suite and inspect:

```text
jdk.VirtualThreadPinned
```

where applicable.

Record stacks and duration of meaningful events.

The goal is not an artificial "zero pin" metric.

The goal is to avoid long avoidable pins caused by VFS design choices such as blocking file-lock acquisition.

---

# 24. Performance work comes after correctness

Do not optimize before native lock interoperability and fault tests pass.

Later benchmarks should include:

## File operations

- sequential 4 KiB reads;
- random 4 KiB reads;
- sequential writes;
- random writes;
- truncate;
- force;
- lock/unlock transitions.

## Coordinator

- SHARED acquire/release;
- RESERVED attempt;
- EXCLUSIVE promotion;
- 1 / 8 / 64 / 256 local connections.

## Baselines

Compare Java VFS primitives with equivalent Java NIO direct calls.

Do not initially compare SQL throughput because no SQL engine is in this repository.

---

# 25. Testing matrix

## OS

Mandatory:

```text
Linux x86_64
macOS arm64
Windows x86_64
```

Add Linux arm64 when CI resources permit.

## Filesystems

CI native filesystem is the minimum.

Record the actual filesystem where possible.

Manual extended runs may include:

```text
ext4
xfs
APFS
NTFS
```

Network filesystems are unsupported until explicitly investigated.

## Threading

```text
single platform thread
single virtual thread
multiple platform threads
multiple virtual threads
separate JVM processes
native SQLite process + Java VFS process
```

---

# 26. Native-interoperability regression suite

These tests are mandatory and permanent.

They specifically prevent recurrence of the sqlite4j/WASI failure that motivated this project.

## Test A

```text
Java VFS holds writer lock
native SQLite BEGIN IMMEDIATE
=> must fail BUSY
```

## Test B

```text
native SQLite holds BEGIN IMMEDIATE
Java VFS writer lock
=> must fail BUSY
```

## Test C

```text
Java VFS SHARED reader
native writer progression
=> behavior matches native SQLite lock protocol
```

## Test D

```text
native reader
Java VFS writer progression
=> behavior matches native SQLite lock protocol
```

## Test E

```text
two Java processes using the Java VFS
=> same results as two native SQLite processes
```

## Test F

Path aliases:

```text
same file through symlink/canonical alias/hard link where supported
=> same-JVM coordinator and OS lock behavior remain safe
```

If any of A-E fails, normal host-backed SQLite compatibility is not achieved.

---

# 27. External SQLite behavior oracle

Use native SQLite/xerial as an oracle, not as production code.

Record:

```text
SQLite version
OS
JDK
native driver version
test database path
locking mode
journal mode
```

Where native SQLite behavior differs by OS, reproduce and model the OS-specific behavior instead of forcing one universal expectation.

---

# 28. Source-reading requirement

For each method implemented, read the matching source in the selected SQLite version.

At minimum inspect:

```text
src/os.c
src/os_unix.c
src/os_win.c
src/pager.c
src/wal.c
sqlite3.h
```

Do not implement locking only from secondary explanations.

For each difficult method, leave a source-reference comment containing:

```text
SQLite source file
function name
reference version
```

Avoid large copied code blocks.

The Java code should express the same semantics, not mechanically transliterate C.

---

# 29. Model-based testing

Use a model for file and lock state independent from the implementation.

Generate sequences such as:

```text
open A
open B
A shared
B shared
A reserved
B reserved
B unlock none
A exclusive
A unlock shared
close A
close B
```

Compare:

```text
model expected result
implementation result
```

Use fixed seeds in failure output.

Store minimized failing sequences.

This is particularly important because locking bugs often require non-obvious state transition orders.

---

# 30. Crash testing once an engine exists

Do not fake crash-safety validation before the engine is connected.

After rollback-journal integration, use child JVMs and terminate them at controlled transaction phases.

Validate resulting files using native SQLite.

Scenarios:

```text
kill before journal sync
kill after journal sync
kill during DB page write
kill immediately after commit result
kill with hot journal present
```

Because process kill is not power-cut simulation, document that limitation.

Power-loss durability remains bounded by the Java filesystem sync guarantees established in Phase 6.

---

# 31. Later JDBC integration strategy

Only after the engine + VFS passes rollback-journal acceptance:

Take the **latest xerial JDBC Java layer** as the compatibility baseline.

Do not begin by forking all of xerial.

First classify its code into:

```text
JDBC/API layer
connection/config layer
DB abstraction
NativeDB/JNI implementation
native loader/build
tests
```

Keep/reuse the JDBC behavior and tests where licensing permits.

Replace the native backend with a new pure-Java engine backend.

Conceptually:

```text
xerial-style JDBC classes
        |
        v
PureJavaDB
        |
        v
JVM SQLite engine
        |
        v
this Java VFS
```

The final runtime must not contain:

```text
JNI
FFM
native library extraction
platform native binaries
```

Treat xerial as JDBC compatibility/reference material, not the architectural center of the VFS project.

---

# 32. Engine strategy after VFS completion

Once the VFS is proven, run a separate feasibility study.

Compare:

## A. Wasm as build-time IR only

```text
SQLite C
 -> wasm32
 -> AOT Java bytecode
 -> ship Java classes only
```

Runtime remains pure Java.

This is the lowest-risk engine path already demonstrated by sqlite4j-style work.

## B. Direct C/LLVM -> JVM

Investigate whether a maintained compiler can correctly handle SQLite's C semantics, including:

- pointer arithmetic;
- unions;
- function pointers;
- integer overflow assumptions;
- atomics;
- generated parser code;
- callbacks.

Do not choose this route only to remove a build-time Wasm artifact.

## C. Manual pure-Java SQLite engine rewrite

Treat as a last resort.

The VFS is a bounded subsystem.

SQLite's parser/planner/VDBE/pager/B-tree implementation is not.

Do not expand this repository into a manual SQL-engine rewrite without a separate explicit decision.

---

# 33. "No Wasm" definition

Before claiming the final project is "without Wasm", specify which statement is meant.

### Level 1

```text
No Wasm runtime/interpreter.
```

Generated Java bytecode is executed directly.

### Level 2

```text
No .wasm artifact shipped.
```

Wasm may exist only as a build-time intermediate.

### Level 3

```text
No Wasm step anywhere in the source/build pipeline.
```

This requires a different SQLite C -> JVM strategy.

VFS completion directly enables Levels 1 and 2 architectures, but does not itself solve Level 3.

---

# 34. Quality gates

## Gate 1 — core I/O

Pass all read/write/truncate/path tests.

## Gate 2 — local lock model

State-machine and jcstress tests pass.

## Gate 3 — cross-process native interoperability

Linux/macOS/Windows native SQLite and Java VFS mutually observe locks.

This is the most important gate.

## Gate 4 — sync semantics documented

File and directory sync guarantees are measured and documented per platform.

## Gate 5 — fault injection

No resource leaks or state corruption under injected I/O failures.

## Gate 6 — VT behavior

No hidden threads and no avoidable long lock-related pinning.

## Gate 7 — rollback-journal engine integration

Only in later integration work.

## Gate 8 — WAL

Separate approval after rollback journal.

---

# 35. Stop conditions

Stop and report evidence if:

1. Java NIO cannot acquire locks compatible with native SQLite on a supported OS;
2. same-JVM coordination cannot preserve native cross-process lock visibility;
3. Windows native SQLite locking cannot be reproduced safely with pure Java APIs;
4. an essential VFS primitive requires native code for correctness, not merely performance;
5. directory synchronization prevents the claimed durability level;
6. WAL requires a native shared-memory primitive unavailable from Java and no correct file-backed alternative matches SQLite;
7. a proposed workaround creates a separate locking protocol invisible to native SQLite.

Do not fall back to:

```text
dotfile locks
JVM-only locks
single-process-only behavior
```

and still label the result as ordinary SQLite-compatible VFS.

Those may be separate explicitly named modes later, but they do not satisfy the main goal.

---

# 36. Commit discipline

Create commits by responsibility.

Suggested sequence:

```text
build: initialize Java 25 VFS project
feat: define SQLite VFS result and flag model
feat: implement positional file I/O
test: cover short-read and partial-write semantics
feat: implement path and temporary-file handling
feat: model SQLite lock state transitions
feat: implement process-local lock coordinator
feat: implement POSIX byte-range lock backend
test: add native SQLite cross-process lock oracle
feat: implement Windows lock backend
test: add Windows native lock interoperability
feat: implement sync and durability primitives
test: add fault-injectable filesystem
test: add jcstress lock coordinator suite
test: add virtual-thread VFS stress suite
docs: record platform guarantees and unresolved limits
```

Do not commit generated benchmark results unless the repository explicitly stores baseline data.

Do not mix formatting-only changes into functional commits.

---

# 37. Agent working rules

This is intended as a substantial autonomous implementation task.

Proceed through the plan without asking for approval for ordinary engineering decisions.

When an implementation choice is uncertain:

1. inspect SQLite source;
2. inspect Java 25 API behavior;
3. build the smallest reproducer;
4. test against native SQLite;
5. choose based on evidence.

Ask for human input only when the decision changes the project's externally visible goal, such as:

- dropping a supported OS;
- accepting weaker cross-process safety;
- accepting weaker durability guarantees;
- introducing native runtime code;
- abandoning native SQLite interoperability.

Do not stop merely because one approach failed.

Try the next evidence-backed approach unless a stop condition is reached.

---

# 38. Per-phase report

At the end of each phase, produce:

```text
current commit SHA
files changed
tests added
exact commands run
OS/JDK
pass/fail counts
native SQLite/xerial reference version
cross-process results
known deviations from SQLite reference
performance data if measured
JFR data if measured
next phase
```

For a failed gate include:

```text
minimal reproducer
expected behavior
actual behavior
SQLite reference behavior
relevant Java exception/result
why the next workaround would or would not preserve compatibility
```

---

# 39. Initial implementation checklist

Start here.

1. Read current SQLite VFS and file-method definitions.
2. Read current Unix and Windows locking implementations.
3. Create the fresh Java 25 repository.
4. Define result codes, flags, lock levels, and file interfaces.
5. Implement `xRead`, including zero-fill + `SHORT_READ`.
6. Implement `xWrite`, truncate, file size, close.
7. Add temp-file and delete-on-close handling.
8. Implement path identity.
9. Write the lock-state model before OS locking code.
10. Implement same-JVM coordinator.
11. Implement POSIX backend with non-blocking `tryLock`.
12. Build the native xerial subprocess oracle.
13. Prove two-way lock interoperability on macOS/Linux.
14. Implement and verify Windows behavior independently.
15. Investigate file and directory sync.
16. Add fault injection.
17. Add jcstress.
18. Add JDK 25 Virtual Thread/JFR tests.
19. Only after all of the above, design the engine adapter.

---

# 40. Primary references

SQLite VFS overview:

https://sqlite.org/vfs.html

SQLite `sqlite3_vfs`:

https://sqlite.org/c3ref/vfs.html

SQLite `sqlite3_io_methods`:

https://sqlite.org/c3ref/io_methods.html

SQLite locking:

https://sqlite.org/lockingv3.html

SQLite WAL:

https://sqlite.org/wal.html

SQLite source tree:

https://sqlite.org/src/

xerial sqlite-jdbc:

https://github.com/xerial/sqlite-jdbc

JDK 25 Virtual Threads:

https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html

For implementation details, the selected SQLite source revision is authoritative over this plan.
