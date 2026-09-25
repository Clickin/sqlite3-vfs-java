# Java SQLite VFS

[![Native SQLite conformance](https://github.com/Clickin/sqlite3_vfs/actions/workflows/conformance.yml/badge.svg)](https://github.com/Clickin/sqlite3_vfs/actions/workflows/conformance.yml)

Java 25의 SQLite OS-interface 구현. 핵심 산출물은 **VFS**이며 SQL 엔진은 독립된 `integration-engine` 모듈에서 실제 SQLite pager와의 연결을 검증한다. Production VFS에 SQLite native library, JNI/FFM, executor 의존성은 없다.

## 구현 범위

- `NioVfs`: open flags/readonly fallback/temp/delete-on-close, delete/access/fullPathname, SQLite extended result codes, 난수·시간·sleep·진단.
- `RollbackFile`: positional I/O, short-read zero-fill, 64-bit offset, truncate, sync, SHARED/RESERVED/PENDING/EXCLUSIVE 잠금 및 same-JVM descriptor 수명 조정.
- `SharedMemoryFile`: 실제 `-shm` 파일 mapping, native SQLite와 공유하는 WAL lock bytes, 연결별 소유권, last-close 정리.
- `adapter/VfsBridge`: 엔진 독립 guest-memory/handle 경계. 페이지 I/O에 재사용 buffer와 generation-tagged handle table을 사용한다.
- `integration-engine`: SQLite 3.53.4 C → Wasm → Endive AOT JVM bytecode, custom `sqlite3_vfs`, sqlite4j 기반 JDBC. 일반 DB open은 host 파일에 직접 접근하며 snapshot filesystem을 사용하지 않는다.

**WAL의 deterministic unmap은 JDK 내부 `sun.misc.Unsafe.invokeCleaner`를 사용한다.** 추가 native binary는 없지만 Java SE API만으로 구성된 구현은 아니다. `jdk.unsupported`와 해당 JDK 기능이 필요하며 JDK 25에서 terminal-deprecation 경고가 발생한다. 지원하지 않는 runtime에서는 WAL capability를 거부한다.

## 실행

JDK 25, Maven Central 접근 필요. root VFS는 JDK 외 production dependency가 없다.

```sh
./mvnw -B --no-transfer-progress install

# 실제 SQL 엔진 검증: 최초 빌드에서 pinned SQLite/WASI SDK 다운로드
bash integration-engine/native/build.sh
./mvnw -B --no-transfer-progress -f integration-engine/pom.xml verify

# real-file coordinator jcstress
./mvnw -B --no-transfer-progress -f stress/pom.xml package
(cd stress/target && java -jar jcstress.jar -t 'io.github.clickin.sqlitevfs.stress.*' -m quick -iters 1 -time 100 -f 1 -fsm 1 -c 2 -strideSize 1 -strideCount 1 -r results)

# Virtual Thread/JFR 및 primitive 성능 baseline
java --illegal-native-access=deny -cp target/test-classes:target/classes \
  io.github.clickin.sqlitevfs.VfsDiagnostics all 128 target/diagnostics
```

Windows는 `mvnw.cmd`, classpath 구분자 `;`를 사용한다. 엔진 빌드 입력 `.wasm`은 Linux/macOS에서 생성하거나 Actions의 `sqlite-engine-input` artifact를 받는다. 생성된 JVM 엔진은 Windows에서도 같은 입력으로 빌드·실행한다.

JDBC artifact `io.github.clickin:sqlite3-vfs-jdbc:0.1.0-SNAPSHOT`은 현재 로컬 빌드용이며 Maven Central에 배포하지 않았다. JDBC API namespace는 `io.roastedroot.sqlite4j`, URL은 `jdbc:sqlite:/absolute/path.db`다. xerial과 이 driver를 같은 application classpath에 넣으면 URL 선택이 모호해지므로 함께 배포하지 않는다.

## 검증

2026-09-25 로컬 macOS ARM64/Temurin 25.0.2/APFS 및 Docker Linux ARM64/Temurin 25.0.4/overlay에서 각각:

| suite | 결과 |
|---|---:|
| VFS, native interop, fault injection, lock model, SHM lifecycle | 108 passed, 0 failed/error/skipped |
| 실제 JVM SQL, native interop, hot-journal recovery, WAL, allocator/callback lifecycle | 39 passed, 0 failed/error/skipped |

모든 native oracle는 별도 JVM의 xerial 3.53.4.0 / SQLite 3.53.4다. parent test JVM은 `--illegal-native-access=deny`로 실행한다. fixture는 성공을 가짜로 반환하는 filesystem이 아니라 실제 channel에 오류를 주입한다.

검증에는 양방향 writer 충돌, reader snapshot, PENDING 신규 reader 차단, process-kill rollback 복구, 5,001행/20,484,096 byte WAL payload와 두 번째 SHM region, checkpoint, readonly main DB, stale SHM header 복구, close 순서, alias 해제가 포함된다. 별도 packaged-driver 실행에서도 WAL commit/rollback 후 native Python SQLite가 같은 DB를 갱신하고 `integrity_check=ok`를 반환했다.

GitHub 3-OS의 최신 실행과 상세 gate 판정은 [구현 결과](IMPLEMENTATION-RESULTS.md)에 기록한다. Actions는 VFS/jcstress/JFR와 엔진 acceptance를 분리하고 JUnit XML·JFR·baseline·엔진 JAR를 artifact로 보존한다.

## 호출 및 지원 계약

- 파일 I/O는 transaction lock을 자동 획득하지 않는다. pager/호출자가 적절한 lock을 먼저 보유해야 한다.
- EXCLUSIVE promotion의 BUSY는 이전 상태 복원을 뜻하지 않는다. PENDING이 남을 수 있다.
- lock/unlock/probe 실패는 공유 identity를 실패 처리한다. 정상 최종 close 뒤에만 reopen 가능하다. **descriptor close 자체가 실패하면 JVM 재시작까지 격리**한다. 이는 자원 누수 없음의 보장이 아니다.
- 같은 파일은 같은 classloader의 coordinator로 접근한다. 다른 라이브러리의 raw open/close가 POSIX lock을 지우는 문제를 방지할 수 없다.
- 열린 파일 경로는 안정적이어야 한다. concurrent rename/unlink/replacement, network filesystem은 지원 대상으로 검증하지 않았다. hardlink의 lock identity 공유는 서로 다른 이름의 journal/SHM 호환성을 뜻하지 않는다.
- file I/O thread의 `Thread.interrupt()` cancellation은 공유 channel을 닫을 수 있어 지원하지 않는다. JDBC SQL interrupt는 별도의 cooperative progress callback을 사용한다.
- Windows explicit directory sync는 `SQLITE_IOERR_DIR_FSYNC`로 실패한다. native Windows와 같은 ordinary file-sync 경로와 별개이며 portable power-loss durability를 주장하지 않는다. process-kill recovery는 power-cut 검증이 아니다.
- NIO에 portable errno가 없으므로 알 수 없는 disk-full 오류는 해당 operation의 IOERR로 매핑한다. localized exception 문자열로 SQLITE_FULL을 추측하지 않는다.
- 처음 Java WAL mapping을 붙일 때 native transaction이 활성 상태이면 `SQLITE_BUSY_RECOVERY`를 반환할 수 있다. quiescent attachment 이후에는 양쪽 reader/writer의 ordinary WAL 동시 접근을 검증했다. 초기화가 필요한 readonly SHM은 `READONLY_CANTINIT`; readonly main DB + writable sidecars는 지원한다.
- `xDeviceCharacteristics=0`, sector size 4096은 보수적 값이며 물리 sector 탐지가 아니다. database `xFetch`/mmap, native extension loading, shared-cache는 지원하지 않는다.

## Runtime 및 Wasm 구분

root VFS에는 Wasm dependency가 없다. 선택적 엔진은 Endive runtime/wasm/wasi/log/annotations 1.0.1과 slf4j-api 2.0.18에 의존한다. xerial/JUnit은 test-only, jcstress/JNA는 stress harness-only다. 배포 JAR와 runtime dependency JAR에서 `.dll/.so/.dylib/.jnilib`는 발견되지 않았다.

SQL 명령은 AOT Java bytecode로 실행한다. 그러나 `SQLiteModule.meta`에 Wasm-format metadata가 남고 Endive parser/runtime API가 필요하다. 실제 metadata의 2,645개 code body는 모두 `unreachable` stub이었다. **명령어 interpreter가 필요 없다는 것과 Wasm runtime/artifact가 전혀 없다는 것은 다르다.** PLAN의 엄격한 no-Wasm Level 1/2/3을 달성했다고 주장하지 않는다.

## 자료

- [원래 계획](PLAN-pure-java-sqlite-vfs.md), [전체 항목 판정과 실행 근거](IMPLEMENTATION-RESULTS.md)
- [VFS feasibility 조사](RESEARCH-pure-java-sqlite-vfs.md)
- [Wasm/JVM 경로](ENGINE-WASM-RESEARCH.md), [C/LLVM→JVM 대안](ENGINE-LLVM-RESEARCH.md)
- [고정 reference와 source-reading 근거](reference-sqlite.properties)
- [vendored engine provenance/licenses](integration-engine/native/THIRD-PARTY.txt)
