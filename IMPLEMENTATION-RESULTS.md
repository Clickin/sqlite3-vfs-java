# Implementation results — pure Java SQLite VFS

## 판정

VFS와 실제 JVM SQLite 엔진의 rollback/WAL 파일 상호운용을 구현했다. **모든 환경에서 native와 동일한 내구성, 모든 Java SE runtime의 WAL 지원, 완전한 no-Wasm 엔진을 달성했다는 뜻은 아니다.** 아래 중단 조건은 성공으로 바꾸지 않는다. 주 작업은 VFS이며 JDBC/engine 모듈은 별도 acceptance consumer다.

Reference: SQLite 3.53.4, xerial 3.53.4.0, source-id `2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc`. 다운로드 SHA와 읽은 원본 함수는 `reference-sqlite.properties`, vendored JDBC/C helper provenance는 `integration-engine/native/THIRD-PARTY.txt`에 고정했다.

## PLAN 항목별 결과

| PLAN 절 | 산출물 / 판정 |
|---|---|
| 0–3: 경계, 원칙, bootstrap, version | public repository, Java 25 Maven wrapper. dependency-free VFS와 별도 engine/stress 모듈. Java SE-only WAL은 아래 제한 적용. |
| 4–5: API/상수 | `NioVfs`, `RollbackFile`, `SqliteCodes`: C struct 복제 대신 Java resource API, pinned constants 검증. |
| 6: I/O | partial transfers, zero-fill SHORT_READ, >4 GiB offsets, shrink/extend, read-only fallback, temp/delete-on-close, actual flags. `RollbackFileTest`, `NioVfsTest`. |
| 7: path | canonical path/fileKey, null fileKey 시 same-file 비교, aliases, access/delete/fullPathname. concurrent replacement 미지원. |
| 8–13: OS/local locks와 native protocol | actual SQLite byte ranges, tryLock, local shared counts/owner, PENDING 유지, POSIX deferred descriptor close. native child request/response oracle. OS별 차이는 동작/결과로 검증하며 불필요한 별도 backend class는 만들지 않았다. |
| 14: concurrency | `RollbackFileConcurrencyTest`, four real-file jcstress races. 제한된 interleaving 관측이지 전체 proof는 아니다. |
| 15: sync | file force 및 Unix directory force. Windows explicit directory force 실패를 IOERR_DIR_FSYNC로 전달. portable native-equivalent directory durability **중단 조건**. |
| 16–17: faults/errors | actual-channel fault seams, primary/suppressed exceptions, operation-specific extended codes. uncertain descriptor close는 JVM-restart quarantine; 무누수 보장 gate는 충족하지 못한다. errno 미노출 disk-full은 IOERR. |
| 18–19: 나머지 VFS/capability | random/sleep/Julian time/last error; extension load unsupported; device0/sector4096; LOCKSTATE, MMAP_SIZE0, POWERSAFE_OVERWRITE0; fetch unsupported. |
| 20: adapter | `EngineMemory`, `VfsBridge`: engine-independent checked guest ranges, little endian ABI, generation handles, reusable page buffer. |
| 21: rollback engine | custom OS_OTHER C VFS, actual SQLite C→AOT JVM engine. create/reopen/transactions/savepoints/VACUUM/ATTACH/temp/multiple connections/backup. |
| 22: WAL | real shared mapping + native byte locks + live guest aliases. both writer directions/snapshots/checkpoint/close orders/second SHM region/readonly DB/stale-index recovery. first attachment BUSY_RECOVERY contract and JDK-internal cleaner requirement below. |
| 23: VT/JFR | platform8, VT1, VT64 checked I/O/locks. JFR pin/duration events and baseline artifact. 0 recorded pins is not nonblocking-I/O proof. |
| 24: performance | raw NIO vs VFS seq/random 4KiB, truncate, force, lock cycles, fan-in1/8/64/256. no SQL performance or cache-controlled benchmark claim. |
| 25–28: matrix/oracle/reference | macOS/APFS, Linux/overlay local, GitHub 3-OS workflow; native subprocess remains test-only. pinned Unix/Windows/pager/WAL source evidence. network FS unqualified. |
| 29: model | four fixed seeds × 2,000 lock operations, independent state model. actual fault/IO tests supplement it. |
| 30: crash | real VFS callback crash points before journal sync/after journal sync/after DB write/after commit; native recovery then JVM reopen; reverse native dirty-spill kill. process failure only, not physical power cut. |
| 31: JDBC | sqlite4j Java layer with latest pinned xerial API comparison; native loader/ZeroFS removed, direct live VFS backend. callback unwind, malloc failure, function ownership and closed-deserialize regressions. not full xerial compatibility certification. |
| 32: strategies | Wasm AOT selected; C/LLVM alternatives investigated in ENGINE-LLVM-RESEARCH.md. no speculative engine rewrite. |
| 33: no-Wasm | VFS core has no Wasm. engine executes AOT JVM bytecode but retains Endive runtime/parser + Wasm metadata: strict Levels1/2/3 **not claimed**. |
| 34–35: gates/stops | basic IO/locks/native interoperability/fault mapping/engine behavior exercised; strict portable durability, leak-free close failure, Java SE-only WAL and broad production certification are not established. |
| 36–38: commits/reports | staged historical commits/runs and current results below; source/test/docs committed together with explicit qualifications. |
| 39–40: initial checklist/references | corresponding implementation/tests above; original PLAN retained as specification, not rewritten into a retroactive success claim. |

## WAL initialization and its limit

NIO cannot distinguish Unix `F_GETLK`'s live shared DMS owner from an exclusive initializer that dies before truncating SHM. Merely probing exclusive then obtaining shared DMS can expose a stale valid wal-index. The permanent `SharedMemoryDmsCapabilityTest` demonstrates that ambiguity; `ShmFileTest` preserves a stale valid index and reproduces the crash window.

The implementation uses SQLite-visible locks, not a private protocol: on ambiguous first attachment, obtain all eight WAL lock bytes exclusively, invalidate the second and first index headers positionally before publishing shared DMS ownership, then map/release the guard locks. Native SQLite rebuilds the invalid index from the WAL. Native transaction activity can prevent this initial guard acquisition, returning `SQLITE_BUSY_RECOVERY`. After that activity ends, retry succeeds. Plain BUSY would be interpreted by SQLite as transient DMS initialization and eventually SQLITE_PROTOCOL, so the C adapter returns the recovery-specific extended code.

This is a documented availability difference from native Unix F_GETLK, not a silent claim of identical behavior. Existing attached native/JVM readers and writers use the ordinary WAL protocol. Main-file SHARED ownership and stable paths remain preconditions. The current implementation uses deprecated `sun.misc.Unsafe.invokeCleaner`; unsupported runtime must not silently rely on GC cleanup. This is not an absence of a standard alternative: Java 22+ `FileChannel.map(..., Arena)` provides deterministic unmap on arena close, but is not implemented here and conflicts with the PLAN's blanket exclusion of FFM APIs, despite not requiring native downcalls. Writable SHM is required for bootstrap even when the main DB is read-only.

## Gate failures kept explicit

| Reproducer | Expected/requested | Observed | Disposition |
|---|---|---|---|
| Windows directory open + `force(true)`; `NioVfs.delete(path,true)` | directory durability requested | AccessDeniedException / IOERR_DIR_FSYNC | no successful no-op; portable directory durability not claimed |
| fault decorator: actual descriptor close throws before releasing OS resource | no lock/resource leak | Java channel may report closed while OS lock remains | quarantine identity until JVM restart; no leak-free guarantee |
| failed exclusive DMS probe followed by shared probe | distinguish live attachment from dead initializer | identical observations in both cases | all-WAL-byte guard + invalidation; BUSY_RECOVERY while transactions active |
| runtime without supported deterministic mapping cleaner | timely SHM unmap/delete | current MappedByteBuffer implementation lacks an explicit standard close | current WAL capability unavailable; standard Arena mapping is an unimplemented alternative |
| inspect engine JAR `SQLiteModule.meta` | no Wasm metadata/runtime | Wasm magic, Endive dependencies | no instruction interpreter requirement, but strict no-Wasm gates not met |

## Executed local verification

Commands are in README and `.github/workflows/conformance.yml`. macOS ran root `install`, native `build.sh`, engine `verify`; Docker ran the same installed-root/engine sequence plus jcstress and diagnostics with separate target mounts. Linux database fixtures were on container overlay, not the macOS source bind mount.

| Environment | Core | Engine | Failures/errors/skips |
|---|---:|---:|---:|
| macOS ARM64, Temurin25.0.2, APFS | 108 | 39 | 0/0/0 |
| Docker Linux ARM64, Temurin25.0.4, overlay | 108 | 39 | 0/0/0 |

Additional standalone packaged JDBC run used `--illegal-native-access=deny` with only runtime dependencies: WAL create/commit/rollback returned value41 and integrity_check=ok; independent Python native SQLite read41, committed42, and returned integrity_check=ok. No xerial was on that application classpath.

JAR audit found no `.dll`, `.so`, `.dylib`, `.jnilib` in the engine/VFS/runtime dependency JARs. Endive1.0.1 runtime/wasm/wasi/log/annotations and slf4j-api2.0.18 remain required. `SQLiteModule.meta` is123,497 bytes, starts with Wasm magic, and all2,645 code bodies are `00 00 0b` (zero locals, unreachable, end). SQL ran successfully through generated Java code; the metadata itself cannot execute those SQL functions.

Linux JFR: 0 recorded pins, 4,729 file-read events, 260 file-write events. This does not prove zero carrier occupancy. Local Linux single-handle force: raw NIO125,173ns/op, VFS95,871ns/op; 256-handle serialized EXCLUSIVE cycle: raw2,306ns/op, VFS2,256ns/op. Measurements include overhead and uncontrolled cache/warmup; do not interpret these samples as a speedup claim. The production implementation creates no workers.

Local macOS and Linux jcstress reports both recorded **4/4 test classes passed**, with no failed/error classes. These are short real-file races, not exhaustive concurrency verification.

The engine ownership regression suite was also run in an isolated source copy with the three pre-fix Java files and the same memory-accounting-enabled SQLite build: **10 tests, 6 failures, 3 errors**. Observed failures included allocator exhaustion causing guest traps instead of NOMEM, callbacks abandoning statements/transaction work, cross-engine function rebinding, and closed-deserialize allocation leakage. The corrected source passed all ten within the 39-test suite on both local OSes. The temporary baseline copy was removed.

## GitHub evidence

- Initial12-test3-OS run: [36120034297](https://github.com/Clickin/sqlite3_vfs/actions/runs/36120034297), `98fed0001e35a4afee5cdb3d10d6f3eadddf7cf0`.
- Fault/concurrency45-test3-OS run: [36122200402](https://github.com/Clickin/sqlite3_vfs/actions/runs/36122200402), `fde1f77fbc6ffe9089a3848523f34e09455d6cbc`.
- Complete rollback VFS/adapter87-test3-OS run: [36124924664](https://github.com/Clickin/sqlite3_vfs/actions/runs/36124924664), `91e1e88595906eaec10fe233d6e96e00e9b3583b`.

### Expanded final acceptance

[Actions run 36134552143](https://github.com/Clickin/sqlite3_vfs/actions/runs/36134552143), source commit [`255124c80be957afa895a28584affaaa2428a8e9`](https://github.com/Clickin/sqlite3_vfs/commit/255124c80be957afa895a28584affaaa2428a8e9): **all seven jobs succeeded**. Downloaded artifacts were inspected, rather than relying only on the workflow badge.

| Runner | JDK | Core | Engine | jcstress classes | JFR recorded pins |
|---|---|---:|---:|---:|---:|
| ubuntu-24.04 x64 | Temurin25.0.4.1 | 108 passed | 39 passed | 4/4 passed | 0 |
| macos-15 ARM64 | Temurin25.0.4.1 | 108 passed | 39 passed | 4/4 passed | 0 |
| windows-2025 x64 | Temurin25.0.4.1 | 108 passed | 39 passed | 4/4 passed | 0 |

All six JUnit artifact groups report **0 failures, 0 errors, 0 skips**. All three diagnostic summaries report successful workload behavior checks. JFR file-read/write event counts were Linux3,785/282, macOS6,164/333, Windows6,458/385. Artifacts include JUnit/native subprocess output, JFR recordings, primitive baseline summaries, jcstress reports and the actual engine JAR.

This closes the plan's implementation/investigation pass with the explicit stop conditions above. It is not a claim that the unresolved portability/durability/no-Wasm constraints disappeared.

## Windows directory-sync diagnosis and guest power cuts

[Controlled Windows experiment 36137237917](https://github.com/Clickin/sqlite3_vfs/actions/runs/36137237917) compared Java and direct Win32 handles on the same directory. The runner reported Microsoft Corporation / Virtual Machine / HypervisorPresent=True, Windows Server 2025 build26100, C: and D: NTFS, Azure westcentralus, and no Windows container marker. Docker was running as a service, but the workflow executes PowerShell and Java directly, without a job container.

The original failing run36134552143 logs were also retrieved with authenticated `gh`: Azure westcentralus, windows-2025-vs2026 image20260907.229.1, NTFS WindowsFileSystemProvider, and the recorded directory AccessDeniedException. The controlled run used that same image revision; the original failure was on C:, while the controlled comparison used D:.

| Directory operation | Observed result |
|---|---|
| Java FileChannel.open, READ or WRITE | AccessDeniedException before force |
| Win32 CreateFile, no BACKUP_SEMANTICS | error5 for all tested access modes |
| BACKUP_SEMANTICS + GENERIC_READ | open succeeds; FlushFileBuffers fails with error5 |
| BACKUP_SEMANTICS + GENERIC_WRITE or READ/WRITE | open succeeds; FlushFileBuffers succeeds |

The source trace was inspected at **OpenJDK jdk-25-ga and jdk-25.0.4-ga**; this is upstream source, not a byte-for-byte verification of the exact Temurin25.0.4.1 binary. [WindowsChannelFactory](https://github.com/openjdk/jdk25u/blob/jdk-25.0.4-ga/src/java.base/windows/classes/sun/nio/fs/WindowsChannelFactory.java) maps READ to GENERIC_READ but does not set FILE_FLAG_BACKUP_SEMANTICS. [Microsoft's directory-handle contract](https://learn.microsoft.com/en-us/windows/win32/fileio/obtaining-a-handle-to-a-directory) requires that flag. [WindowsException](https://github.com/openjdk/jdk25u/blob/jdk-25.0.4-ga/src/java.base/windows/classes/sun/nio/fs/WindowsException.java) maps native error5 to AccessDeniedException. Thus the observed failure is the Java/provider directory-open path, not a Docker/Kubernetes storage restriction. Native successful flush on this runner is not proof of portable directory power-loss semantics.

An additional trap: [JDK Windows force0](https://github.com/openjdk/jdk25u/blob/jdk-25.0.4-ga/src/java.base/windows/native/libnio/ch/FileDispatcherImpl.c) ignores ERROR_ACCESS_DENIED from FlushFileBuffers. The experiment observed Java READ-only ordinary-file force returning normally while a direct native READ-only flush failed with error5. A normal return on a READ-only handle is therefore not sufficient evidence that a Windows flush occurred.

Then an isolated QEMU11.1.1/HVF Ubuntu24.04.4 ARM64 VM was power-cut with **SIGKILL to the entire QEMU process**, not merely the Java process. A dedicated raw virtio disk contained ext4; the DB was not on a host shared folder. The guest ran Temurin25.0.4, the actual JVM SQLite3.53.4 VFS engine, and independent Python native SQLite3.45.1.

The host fsynced received commit acknowledgements to a separate ledger, waited for the specified VFS/transaction event, killed QEMU, rebooted the same disks and alternated JVM-first/native-first recovery. All six scenarios passed:

| Mode | Cut point | Outcome |
|---|---|---|
| DELETE / synchronous=EXTRA | before journal sync | baseline transaction retained; interrupted transaction absent |
| DELETE / synchronous=EXTRA | after journal sync | baseline retained; interrupted transaction absent |
| DELETE / synchronous=EXTRA | after DB write | baseline retained; interrupted transaction absent |
| DELETE / synchronous=EXTRA | after commit acknowledgement | both acknowledged transactions retained |
| WAL / synchronous=FULL | before commit | baseline retained; interrupted transaction absent |
| WAL / synchronous=FULL | after commit acknowledgement | both acknowledged transactions retained |

Both engines verified row counts, value sums, payload lengths, existing-row updates and integrity_check=ok. Results and the exercised peer/controller are under `diagnostics/`. The controller takes a prepared-VM JSON object with `root` (evidence directory), `qemu` (argument array), and `ssh` (argument array); it assumes the dedicated guest `/dev/vdb` ext4 disk and compiled peer/JDK/runtime JARs have already been prepared. It must only be used with a disposable VM, as every cut kills the entire emulator.

Limits: six deterministic cuts, no Windows guest power-cut trial, and QEMU cache=writeback leaves the host OS/storage caches alive. These are **guest-power-loss recovery results**, not physical power-loss certification or proof that directory sync is unnecessary. The first controller attempt had a three-field expected tuple for a four-field native query; that harness error was corrected before the six reported trials, without changing production code.

## Established-library Windows comparison

[Run36139300447](https://github.com/Clickin/sqlite3_vfs/actions/runs/36139300447) executed the published Lucene10.3.1 and Kafka4.1.0 JARs on Windows Server2025/NTFS/Temurin25.0.4.1, with Maven Central SHA512 checks. The raw JDK directory open+force threw AccessDeniedException. Lucene IOUtils.fsync(directory,true), NIOFSDirectory.syncMetaData and Kafka Utils.flushDirIfExists returned normally. Ordinary-file WRITE+force also returned normally.

The return values are **not evidence that the libraries flushed Windows directories**: [Lucene10.3.1 IOUtils](https://github.com/apache/lucene/blob/releases/lucene/10.3.1/lucene/core/src/java/org/apache/lucene/util/IOUtils.java) returns before opening an existing directory on Windows; [Kafka4.1.0 Utils](https://github.com/apache/kafka/blob/4.1.0/clients/src/main/java/org/apache/kafka/common/utils/Utils.java) skips directory flush on Windows and z/OS. [KAFKA-13391](https://issues.apache.org/jira/browse/KAFKA-13391) records the actual Kafka3.0.0 Windows AccessDeniedException regression; [PR11426](https://github.com/apache/kafka/pull/11426) added the Windows skip.

SQLite3.53.4's native Windows VFS is different from a native directory-flush workaround: `winSync` flushes the file handle, while `winDelete` explicitly marks `syncDir` unused. The xerial native backend therefore does not encounter Java's directory FileChannel open path, but this does not mean it performs a separate directory flush.

The [FileChannel.force contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)) guarantees the specified channel-write paths and describes read-only metadata IO as system-dependent. The observed read-only force return alone does not establish loss of a guaranteed write. A portable directory-durability enhancement is a better-supported JDK proposal than claiming that this observation proves a new data-loss regression.
