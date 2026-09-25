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
- 잠금 획득 오류와 cleanup 오류의 동시 발생 시 원래 오류 보존 및 suppressed error 전달.
- descriptor close 실패 후 불확실한 OS 소유권을 재사용하지 않도록 해당 identity 재개방 차단.

production 의존성은 JDK뿐이다. `org.xerial:sqlite-jdbc:3.53.4.0`과 JUnit은 **test scope**이며, native SQLite 로드는 test child JVM의 `NativeOracle.main`에서만 수행한다. backend 자체는 JNI·FFM·worker/executor를 사용하지 않는다.

### 호출 계약

- `open(path, false)`는 없으면 생성하고, `open(path, true)`는 생성하지 않는다.
- `read()`는 ByteBuffer의 remaining만 채우고 position을 전진시킨다. 결과는 `OK` 또는 zero-fill된 `SHORT_READ`다.
- `lock(SHARED)` 후 `lock(RESERVED)` 또는 `lock(EXCLUSIVE)`를 호출한다. `false`는 BUSY이며 **호출 전 상태로 돌아갔다는 뜻이 아니다**. `level()`로 PENDING을 관측할 수 있다.
- `unlock()` 대상은 SHARED 또는 NONE. retry와 transaction ordering은 호출자 책임이다.
- I/O 실패는 IOException이다. 완전한 SQLite extended result-code mapper는 이 backend의 범위가 아니다.
- buffer I/O는 자동으로 transaction lock을 획득하지 않는다. SQLite pager에 연결하는 adapter는 올바른 잠금을 먼저 보유해야 한다.

### 장애 후 호출 계약

- ordinary read/write/size/truncate/sync의 IOException은 호출자에게 전달하며 보유 잠금을 자동 해제하지 않는다. 부분 write는 이미 기록한 prefix를 되돌리지 않는다. retry/rollback은 호출자의 책임이다.
- lock/probe/unlock 오류는 공유 상태를 실패 처리한다. 모든 logical handle이 추가 I/O·잠금·동일 파일 open을 거부하며, 마지막 descriptor가 성공적으로 닫힌 뒤에만 새로 열 수 있다.
- **descriptor close 자체의 IOException은 별도다.** 해당 identity를 JVM 종료까지 격리하여 read-only/read-write 재개방을 모두 거부한다. JDK는 실제 cleanup 전에 channel을 closed로 표시하므로 `isOpen()==false`나 `close()` 재호출로 OS descriptor 해제를 증명할 수 없다. [JDK close 계약](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/spi/AbstractInterruptibleChannel.html#close())
- close 실패에도 원래 RO channel과 추가 RW channel을 모두 닫으려 시도한다. 격리는 **안전성 조치이지 OS 자원 해제·누수 없음의 보장은 아니다**. 장애 후 JVM을 재시작해야 하며, 격리된 파일 경로도 재시작 전에는 rename/unlink/replace하지 않는다.

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

[`RollbackFileFaultTest`](src/test/java/io/github/clickin/sqlitevfs/RollbackFileFaultTest.java)의 **25개 장애 주입 invocation**:

- 실제 FileChannel에 작은 partial transfer, zero progress, N번째 open/read/write/size/truncate/force/lock/unlock/close 실패 주입.
- 부분 write의 실제 prefix·기존 suffix, EOF와 read 오류 구분, sync 실패 뒤 잠금 유지와 재시도.
- RO → RW open 실패 뒤 기존 reader의 정상 동작.
- 11개 잠금 획득·probe·승격·강등·unlock 실패 경로에서 모든 handle의 실패 전파와 별도 native 프로세스의 잠금 관측.
- 원래 잠금 오류와 cleanup 오류 동시 발생, 마지막 정상 close 뒤 native commit 및 backend 재개방.
- descriptor close가 실제로 수행된 경우/수행되지 않은 경우 모두 재개방 차단. 이 검증은 별도 JVM에서 실행하며 test fixture만 raw descriptor를 정리할 수 있다.

[`RollbackFileConcurrencyTest`](src/test/java/io/github/clickin/sqlitevfs/RollbackFileConcurrencyTest.java)의 **8개 동시성 invocation**: platform/virtual executor 각각에서 동시 SHARED, 단일 RESERVED 승자, PENDING 중 reader 이탈/신규 접근, close와 downgrade 경합, 경쟁 writer들의 실제 누적 write를 검증한다. barrier/latch로 순서를 제어하며, raw header 쓰기의 결과는 기존 SQLite pager 캐시가 아닌 새 native 연결로 확인한다.

전체 **45개 invocation = 기존 12 + 장애 주입 25 + 동시성 8**. fault injector는 test-only real-channel decorator이며 production에는 package-private channel-open 지점만 추가했다. disk/lock 성공 응답을 가짜로 만드는 filesystem은 사용하지 않는다. 동시성 검증은 제한된 결정적 시나리오이며 jcstress나 모든 interleaving의 증명이 아니다.

native oracle는 **별도 JVM**에서 SQLite `journal_mode=DELETE`, `busy_timeout=0`, `synchronous=FULL`로 실행된다. readiness와 번호가 붙은 request/response로 순서를 맞추며 임의 sleep은 없다. 응답·프로세스 종료에는 timeout이 있고 실패 시 child를 종료한다. 지원되지 않는 link 생성은 명시적으로 skip된다.

Directory `force(true)`는 성공/예외를 **capability 관측으로 출력**한다. 실패를 지원 성공으로 바꾸지 않으며, 테스트 전체가 green이어도 directory durability가 확보됐다는 뜻은 아니다.

## 실행 증거

2026-09-25 장애/동시성 단계의 로컬 실행:

| 환경 | JDK | filesystem | 결과 |
|---|---|---|---|
| macOS ARM64 | Temurin 25.0.2 | APFS | 45 passed, 0 failed, 0 skipped |
| 로컬 Docker Linux ARM64 | Temurin 25.0.4 | container overlay | 45 passed, 0 failed, 0 skipped |

두 환경에서 ordinary file sync 및 directory open+force는 예외 없이 완료됐다. 전원 장애 시 내구성 증명은 아니다. Linux DB fixture는 macOS bind mount가 아니라 container 내부 temporary filesystem에 생성된다.

수정 전 같은 45개 invocation에서 5개 실패를 관측했다. backend 문제는 cleanup 오류가 원래 잠금 오류를 덮는 경우 1개와 불확실한 close 뒤 재개방 2개였다. 나머지 2개는 raw header 변경을 기존 native 연결의 pager 캐시로 검사한 테스트 문제였으며 새 연결로 실제 파일을 관측하도록 수정했다. 수정 후 두 로컬 환경에서 45/45 통과했다.

JUnit 외부의 별도 Java 실행에서도 unlock+descriptor-close 오류를 주입했다. backend는 재개방을 거부했고, 남은 실제 OS lock 때문에 Python native SQLite의 BEGIN IMMEDIATE는 BUSY였다. **test fixture가 소유한 raw descriptor를 정리한 후** native UPDATE/COMMIT 및 `integrity_check=ok`를 확인했다. 이는 production이 실패한 descriptor를 복구할 수 있다는 주장이 아니다.

### 장애/동시성 GitHub-hosted 3-OS 실행 (45개)

[Actions run 36122200402](https://github.com/Clickin/sqlite3_vfs/actions/runs/36122200402), 검증 코드 commit [`fde1f77`](https://github.com/Clickin/sqlite3_vfs/commit/fde1f77fbc6ffe9089a3848523f34e09455d6cbc). 각 OS의 JUnit XML 3개와 close-failure child의 native commit/reopen 격리 증거를 확인했다.

| runner | 기본 / 장애 / 동시성 | 전체 | 실패 / 오류 / skip |
|---|---|---|---|
| ubuntu-24.04 x64 | 12 / 25 / 8 | 45 passed | 0 / 0 / 0 |
| macos-15 ARM64 | 12 / 25 / 8 | 45 passed | 0 / 0 / 0 |
| windows-2025 x64 | 12 / 25 / 8 | 45 passed | 0 / 0 / 0 |

세 runner 모두 Temurin 25.0.4.1과 native SQLite 3.53.4를 사용했다. descriptor close 전/후 오류 두 경우 모두 격리가 검증됐다. Windows directory open+force는 여전히 AccessDeniedException이며 내구성 제한을 완화하지 않았다.

### 초기 GitHub-hosted 3-OS 실행 (12개)

[첫 실제 Actions run](https://github.com/Clickin/sqlite3_vfs/actions/runs/36120034297), backend commit [`98fed00`](https://github.com/Clickin/sqlite3_vfs/commit/98fed0001e35a4afee5cdb3d10d6f3eadddf7cf0). 각 job의 업로드된 JUnit XML과 환경 로그를 확인했다.

| runner | 관측한 환경 | 결과 | directory open+force |
|---|---|---|---|
| ubuntu-24.04 | Linux x64, ext4, Temurin 25.0.4.1 | 12 passed / 0 failed / 0 skipped | 예외 없이 완료 |
| macos-15 | macOS 15.7.9 ARM64, APFS, Temurin 25.0.4.1 | 12 passed / 0 failed / 0 skipped | 예외 없이 완료 |
| windows-2025 | Windows Server 2025 x64, NTFS, Temurin 25.0.4.1 | 12 passed / 0 failed / 0 skipped | **AccessDeniedException** |

세 OS 모두 native ↔ Java 잠금과 4 GiB 초과 I/O를 통과했고 symlink/hardlink 테스트도 skip되지 않았다. **Windows에서는 directory open+force 경로가 AccessDeniedException으로 실패했다.** 따라서 이 방식의 portable directory sync gate는 통과하지 못했다. file sync/locking 성공과 별개의 결과이며 다른 Windows sync 경로의 불가능성까지 증명한 것은 아니다.

실행 중 action의 Node 20 및 setup-java v4 지원 종료 경고를 확인하여 checkout/setup-java는 v5, upload-artifact는 v7.0.1의 Node 24 기반 commit으로 고정했다. 최신 실행은 상단 Actions 링크에서 확인할 수 있다.

추가로 실제 backend를 `--illegal-native-access=deny` JVM에서 실행하고 Python의 native SQLite 3.53.4로 접근했다. Java RESERVED 보유 중 `BEGIN IMMEDIATE`는 SQLITE_BUSY, backend close 후 UPDATE/COMMIT은 성공했고 `value=8`, `integrity_check=ok`를 확인했다. 이는 JUnit 바깥의 별도 실행이며 backend가 SQLite 엔진이라는 의미는 아니다.

native reference source-id:

```text
2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc
```

## 안전성 범위

이 검증은 전체 [PLAN](PLAN-pure-java-sqlite-vfs.md)의 production acceptance를 통과했다는 선언이 아니다.

- **WAL, C/Wasm engine adapter, backend를 통한 SQL 실행, hot-journal recovery, power-cut, jcstress, 부하/JFR 검증은 포함하지 않는다.** 장애 주입은 현재 file/lock backend 범위이며 아직 없는 VFS delete/path/adapter 전체를 검증한 것은 아니다. Virtual Thread 테스트는 pin-free/확장성의 증명이 아니다.
- 기본 filesystem provider의 regular file과 실제 shared lock 지원이 필요하다. network filesystem은 지원 대상으로 검증하지 않았다.
- 같은 JVM에서 같은 파일을 여는 모든 코드는 같은 classloader의 backend를 사용해야 한다. 다른 라이브러리의 raw open/close가 POSIX lock을 지우는 문제는 Java에서 탐지·방지할 수 없다.
- I/O thread를 interrupt하면 공유 channel이 닫힐 수 있다. 그런 cancellation은 지원하지 않는다. 감지한 channel/lock 상실 후에는 추가 작업을 거부하며 잠금을 몰래 재획득하지 않는다. descriptor close 자체도 실패하면 위의 JVM 재시작 계약이 적용된다.
- 열린 파일의 concurrent rename/unlink/replacement는 지원하지 않는다. 표준 Java로 열린 descriptor의 identity를 원자적으로 확인할 수 없으므로 path/open race가 남는다.
- Windows의 null `fileKey()`는 `Files.isSameFile()` 비교로 보완한다. 이 경로는 열린 파일 수에 비례하는 scan이며 경로가 안정적이어야 한다.
- hardlink 테스트는 **잠금 identity**만 검증한다. 서로 다른 basename의 SQLite journal/SHM sidecar가 호환된다는 뜻이 아니다.
- `sync(boolean)`은 file sync이며 SQLite FULL/NORMAL flag mapper나 portable directory fsync가 아니다. power-loss 내구성 동등성을 보장하지 않는다.
- 구현의 수명/동시성 설계는 위 테스트로 검증한 범위다. 이 결과를 임의의 filesystem·vendor JDK·장애 상황까지 확대하지 않는다.

### 엔진 통합 전에 남은 조건

현재 결과는 file/lock 실패 경로의 근거다. 완전한 SQLite VFS의 open/delete/temp/path·extended result-code 의미론, Windows directory-sync의 명시적 내구성 계약, fault injection을 포함한 전체 VFS gate가 남아 있다. 이후 C/Wasm adapter를 연결해 rollback journal 생성·sync·삭제와 hot-journal recovery를 검증해야 한다. 이번 byte-level header 검증은 SQL transaction/recovery 구현을 대체하지 않는다.

## 조사 자료

- [원래 구현 계획](PLAN-pure-java-sqlite-vfs.md)
- [VFS feasibility 조사와 이전 primitive 실험](RESEARCH-pure-java-sqlite-vfs.md)
- [Wasm/Endive/Chicory 엔진 통합](ENGINE-WASM-RESEARCH.md)
- [C/LLVM→JVM 및 다른 변환 경로](ENGINE-LLVM-RESEARCH.md)

위 조사 문서는 backend 구현 전의 기록이다. 현재 구현 범위와 실행 상태는 이 README와 연결된 Actions run을 기준으로 한다.
