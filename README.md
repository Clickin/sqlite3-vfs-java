# Java SQLite rollback VFS backend

[![Native SQLite conformance](https://github.com/Clickin/sqlite3_vfs/actions/workflows/conformance.yml/badge.svg)](https://github.com/Clickin/sqlite3_vfs/actions/workflows/conformance.yml)

Java 25의 파일 I/O와 SQLite rollback-journal 잠금 backend. **SQL 엔진, JDBC driver 또는 완성된 `sqlite3_vfs` C adapter는 아니다.** 순수 Java production 코드와 별도 native SQLite 프로세스 사이의 상호운용성을 실제로 검증한다.

## 실행

JDK 25 이상, 최초 실행 시 Maven Central 접근이 필요하다. Maven은 wrapper로 고정한다.

```sh
./mvnw -B --no-transfer-progress verify
```

Windows PowerShell:

```powershell
.\mvnw.cmd -B --no-transfer-progress verify
```

[GitHub Actions](https://github.com/Clickin/sqlite3_vfs/actions/workflows/conformance.yml)는 동일 명령을 `ubuntu-24.04` (x64), `macos-15` (ARM64), `windows-2025` (x64)에서 실행한다. job별 `conformance-*` artifact에는 JUnit XML, 환경 정보, native SQL 응답이 남는다. 실패한 OS가 있어도 다른 OS는 계속 검증한다. 문서만 바뀌는 push는 재실행하지 않으며 `workflow_dispatch`로 수동 실행할 수 있다.

## 구현된 기능

[`RollbackFile`](src/main/java/io/github/clickin/sqlitevfs/RollbackFile.java)은 다음을 제공한다.

- positional read/write, partial-I/O 반복, EOF 나머지 zero-fill 및 `SHORT_READ=522`.
- 기존 파일을 자르지 않는 open, logical read-only handle, 파일 크기·truncate 확장, file sync.
- SQLite의 실제 PENDING/RESERVED/shared byte range를 사용하는 비차단 OS lock.
- SHARED → RESERVED/EXCLUSIVE, EXCLUSIVE → SHARED/NONE 및 실패한 promotion의 PENDING 유지.
- 같은 JVM의 logical reader/writer 조정, symlink/hardlink identity 처리.
- 마지막 logical handle까지 실제 channel close를 지연하여 다른 연결의 POSIX lock을 보존.
- read-only handle에서도 가능한 reserved-byte shared-lock probe.

production 의존성은 JDK뿐이다. `org.xerial:sqlite-jdbc:3.53.4.0`과 JUnit은 **test scope**이며, native SQLite 로드는 test child JVM의 `NativeOracle.main`에서만 수행한다. backend 자체는 JNI·FFM·worker/executor를 사용하지 않는다.

### 호출 계약

- `open(path, false)`는 없으면 생성하고, `open(path, true)`는 생성하지 않는다.
- `read()`는 ByteBuffer의 remaining만 채우고 position을 전진시킨다. 결과는 `OK` 또는 zero-fill된 `SHORT_READ`다.
- `lock(SHARED)` 후 `lock(RESERVED)` 또는 `lock(EXCLUSIVE)`를 호출한다. `false`는 BUSY이며 **호출 전 상태로 돌아갔다는 뜻이 아니다**. `level()`로 PENDING을 관측할 수 있다.
- `unlock()` 대상은 SHARED 또는 NONE. retry와 transaction ordering은 호출자 책임이다.
- I/O 실패는 IOException이다. 완전한 SQLite extended result-code mapper는 이 backend의 범위가 아니다.
- buffer I/O는 자동으로 transaction lock을 획득하지 않는다. SQLite pager에 연결하는 adapter는 올바른 잠금을 먼저 보유해야 한다.

## 검증 내용

[`RollbackFileTest`](src/test/java/io/github/clickin/sqlitevfs/RollbackFileTest.java)의 12개 test invocation:

- native ↔ Java RESERVED/EXCLUSIVE 충돌 및 해제 후 재획득.
- Java SHARED가 native BEGIN IMMEDIATE는 허용하지만 UPDATE 후 COMMIT은 차단.
- native reader 때문에 Java EXCLUSIVE가 실패한 뒤 PENDING이 신규 reader를 차단.
- reader 프로세스 종료 후 Java EXCLUSIVE 획득.
- local owner/nonowner/reader close 후 남은 연결의 OS lock 보존.
- symlink·hardlink의 local 잠금 및 descriptor 수명 공유.
- RO → RW 추가 open 및 외부 writer에 대한 read-only reserved probe.
- Java backend ↔ 별도 Java backend 프로세스의 양방향 경합.
- short-read zero fill, buffer 경계, truncate 확장, reopen, 4 GiB를 넘는 offset.
- Virtual Thread에서 같은 I/O·잠금 동작.

native oracle는 **별도 JVM**에서 SQLite `journal_mode=DELETE`, `busy_timeout=0`, `synchronous=FULL`로 실행된다. readiness와 번호가 붙은 request/response로 순서를 맞추며 임의 sleep은 없다. 응답·프로세스 종료에는 timeout이 있고 실패 시 child를 종료한다. 지원되지 않는 link 생성은 명시적으로 skip된다.

Directory `force(true)`는 성공/예외를 **capability 관측으로 출력**한다. 실패를 지원 성공으로 바꾸지 않으며, 테스트 전체가 green이어도 directory durability가 확보됐다는 뜻은 아니다.

## 실행 증거

2026-09-25 로컬 실행:

| 환경 | JDK | filesystem | 결과 |
|---|---|---|---|
| macOS ARM64 | Temurin 25.0.2 | APFS | 12 passed, 0 failed, 0 skipped |
| 로컬 Docker Linux ARM64 | Temurin 25.0.4 | container overlay | 12 passed, 0 failed, 0 skipped |

두 환경에서 ordinary file sync 및 directory open+force는 예외 없이 완료됐다. 전원 장애 시 내구성 증명은 아니다. Linux DB fixture는 macOS bind mount가 아니라 container 내부 temporary filesystem에 생성된다.

추가로 실제 backend를 `--illegal-native-access=deny` JVM에서 실행하고 Python의 native SQLite 3.53.4로 접근했다. Java RESERVED 보유 중 `BEGIN IMMEDIATE`는 SQLITE_BUSY, backend close 후 UPDATE/COMMIT은 성공했고 `value=8`, `integrity_check=ok`를 확인했다. 이는 JUnit 바깥의 별도 실행이며 backend가 SQLite 엔진이라는 의미는 아니다.

native reference source-id:

```text
2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc
```

## 안전성 범위

이 검증은 전체 [PLAN](PLAN-pure-java-sqlite-vfs.md)의 production acceptance를 통과했다는 선언이 아니다.

- **WAL, C/Wasm engine adapter, SQL 실행, hot-journal recovery, power-cut, fault injection, jcstress, 부하/JFR 검증은 포함하지 않는다.** Virtual Thread 테스트는 pin-free/확장성의 증명이 아니다.
- 기본 filesystem provider의 regular file과 실제 shared lock 지원이 필요하다. network filesystem은 지원 대상으로 검증하지 않았다.
- 같은 JVM에서 같은 파일을 여는 모든 코드는 같은 classloader의 backend를 사용해야 한다. 다른 라이브러리의 raw open/close가 POSIX lock을 지우는 문제는 Java에서 탐지·방지할 수 없다.
- I/O thread를 interrupt하면 공유 channel이 닫힐 수 있다. 그런 cancellation은 지원하지 않는다. 감지한 channel/lock 상실 후에는 모든 handle을 닫을 때까지 오류를 반환하며 잠금을 몰래 재획득하지 않는다.
- 열린 파일의 concurrent rename/unlink/replacement는 지원하지 않는다. 표준 Java로 열린 descriptor의 identity를 원자적으로 확인할 수 없으므로 path/open race가 남는다.
- Windows의 null `fileKey()`는 `Files.isSameFile()` 비교로 보완한다. 이 경로는 열린 파일 수에 비례하는 scan이며 경로가 안정적이어야 한다.
- hardlink 테스트는 **잠금 identity**만 검증한다. 서로 다른 basename의 SQLite journal/SHM sidecar가 호환된다는 뜻이 아니다.
- `sync(boolean)`은 file sync이며 SQLite FULL/NORMAL flag mapper나 portable directory fsync가 아니다. power-loss 내구성 동등성을 보장하지 않는다.
- 구현의 수명/동시성 설계는 위 테스트로 검증한 범위다. 이 결과를 임의의 filesystem·vendor JDK·장애 상황까지 확대하지 않는다.

## 조사 자료

- [원래 구현 계획](PLAN-pure-java-sqlite-vfs.md)
- [VFS feasibility 조사와 이전 primitive 실험](RESEARCH-pure-java-sqlite-vfs.md)
- [Wasm/Endive/Chicory 엔진 통합](ENGINE-WASM-RESEARCH.md)
- [C/LLVM→JVM 및 다른 변환 경로](ENGINE-LLVM-RESEARCH.md)

위 조사 문서는 backend 구현 전의 기록이다. 현재 구현 범위와 실행 상태는 이 README와 연결된 Actions run을 기준으로 한다.
