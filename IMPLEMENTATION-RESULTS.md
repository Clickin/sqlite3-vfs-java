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

This is a documented availability difference from native Unix F_GETLK, not a silent claim of identical behavior. Existing attached native/JVM readers and writers use the ordinary WAL protocol. Main-file SHARED ownership and stable paths remain preconditions. Deterministic unmap requires deprecated `sun.misc.Unsafe.invokeCleaner`; unsupported runtime must not silently rely on GC cleanup. Writable SHM is required for bootstrap even when the main DB is read-only.

## Gate failures kept explicit

| Reproducer | Expected/requested | Observed | Disposition |
|---|---|---|---|
| Windows directory open + `force(true)`; `NioVfs.delete(path,true)` | directory durability requested | AccessDeniedException / IOERR_DIR_FSYNC | no successful no-op; portable directory durability not claimed |
| fault decorator: actual descriptor close throws before releasing OS resource | no lock/resource leak | Java channel may report closed while OS lock remains | quarantine identity until JVM restart; no leak-free guarantee |
| failed exclusive DMS probe followed by shared probe | distinguish live attachment from dead initializer | identical observations in both cases | all-WAL-byte guard + invalidation; BUSY_RECOVERY while transactions active |
| runtime without supported deterministic mapping cleaner | timely SHM unmap/delete | no Java SE-only guaranteed primitive | WAL capability unavailable; no GC-based fallback |
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

## GitHub evidence

- Initial12-test3-OS run: [36120034297](https://github.com/Clickin/sqlite3_vfs/actions/runs/36120034297), `98fed0001e35a4afee5cdb3d10d6f3eadddf7cf0`.
- Fault/concurrency45-test3-OS run: [36122200402](https://github.com/Clickin/sqlite3_vfs/actions/runs/36122200402), `fde1f77fbc6ffe9089a3848523f34e09455d6cbc`.
- Complete rollback VFS/adapter87-test3-OS run: [36124924664](https://github.com/Clickin/sqlite3_vfs/actions/runs/36124924664), `91e1e88595906eaec10fe233d6e96e00e9b3583b`.

The expanded108-core/39-engine workflow is ready for the final cross-platform run; its observed results must be recorded before claiming that expanded gate passed.
