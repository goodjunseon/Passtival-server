# 인증키 엑셀 적재: JPA와 JDBC 저장 방식 비교

측정일: 2026-09-24 (Asia/Seoul)

애플리케이션 기준 커밋: `2799a43dd1ff3bf0803b9c2095cec47a87079277`

## 결론과 적용 판단

10만 행의 전체 처리 시간 중앙값은 기존 JPA 방식 **4,054.111ms → JDBC 배치 + 재작성 옵션 577.945ms**로 85.74% 줄었다. 같은 입력을 약 7.02배의 처리량으로 저장했다. 저장 구간은 **3,393ms → 204ms**로 93.99% 줄었다. 개선율의 분모는 이번 비교 실험에서 함께 다시 측정한 JPA 결과다.

JDBC 배치만 사용한 경우에도 MySQL INSERT는 10만 번 실행됐다. `rewriteBatchedStatements=true`를 함께 적용하자 100번으로 줄었다. 네 방식 모두 10만 개의 동일한 키를 저장했고, 성공 요청의 커밋은 1회였다. 배치 호출 수, DB INSERT 실행 수, 저장된 행 수는 서로 다른 값이다.

**현재의 단순 인증키 적재 경로에는 JDBC 배치와 재작성 옵션을 적용할 근거가 있다.** 다만 이 결과는 저장 방식의 비교 실험이다. 애플리케이션 API·서비스·엔티티·설정은 변경하지 않았고, 실험용 저장 대안은 `docs/benchmarks/excel-import-comparison` 안에만 있다. 실제 반영 시에는 별도의 JDBC 저장 컴포넌트와 설정으로 구현해야 한다.

## 검증한 가설과 비교군

가설: 행별 INSERT를 배치 처리로 전환하면, 동일한 데이터와 요청 단위 원자성을 유지하면서 적재 시간을 줄일 수 있다.

| 구분 | 저장 호출 | 드라이버 재작성 | 비교 목적 |
| --- | --- | --- | --- |
| A / JPA | 실제 `AuthenticationKeyRepository.saveAll()` + `flush()` | false | 현재 동작 재측정 |
| B / JDBC_SINGLE | 행마다 `JdbcTemplate.update()` | false | JPA 경로를 우회했을 때의 차이 |
| C / JDBC_BATCH | `JdbcTemplate.batchUpdate()`, 배치 크기 1,000 | false | JDBC 배치 호출과 PreparedStatement 재사용 효과 |
| D / JDBC_BATCH_REWRITE | C와 동일 | true | 드라이버 재작성 옵션 효과 |

네 방식 모두 DB의 AUTO_INCREMENT와 엔티티의 IDENTITY 매핑을 유지했다. 요청당 트랜잭션은 1개이며, 배치마다 커밋하지 않는다. Hibernate의 `batch_size`는 모두 0이다.

Spring은 `JdbcTemplate.batchUpdate()`의 배치 크기를 지정하는 API를 제공한다. Connector/J의 `rewriteBatchedStatements`는 `executeBatch()`의 INSERT 등을 여러 값을 담는 SQL로 재작성하는 옵션이다. [Spring 문서](https://docs.spring.io/spring-framework/reference/data-access/jdbc/advanced.html), [Connector/J 문서](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-performance-extensions.html)

## 실험 구성과 통제 조건

실제 `AuthenticationKeyImportService`를 그대로 호출한다. A에는 실제 Spring Data JPA 저장소를 주입하고, B~D에는 측정용 저장소 어댑터를 주입한다. 어댑터는 `saveAll()`을 JDBC로 처리하고, JDBC가 쓰기를 완료한 뒤의 `flush()`는 추가 작업 없이 반환한다. 파싱 코드, 엔티티 리스트 생성, 로그, 반환값, 서비스의 트랜잭션 애너테이션은 공통이다.

트랜잭션 프록시와 `JpaTransactionManager`도 네 방식이 공유한다. 각 측정 환경의 동일한 DataSource를 트랜잭션 매니저와 `JdbcTemplate`에 연결해 JDBC 호출이 서비스 트랜잭션에 참여하도록 했다. 실제 실패 후 DB 조회로 전체 롤백을 검증했다.

| 항목 | 조건 |
| --- | --- |
| 장비 | 앞선 기준선과 동일한 Apple M3 / 메모리 16GiB 장비 |
| OS / JVM | macOS 26.6.2 arm64 / OpenJDK 17.0.20, `-Xms512m -Xmx1024m` |
| 라이브러리 | Spring 6.2.6 / Spring Data JPA 3.4.5 / Hibernate 6.6.13.Final / POI 5.2.5 |
| DB / 드라이버 | MySQL 8.0.46 / Connector/J 9.1.0 |
| 접속 | 별도 임시 MySQL, `127.0.0.1:13318`, `passtival_excel_comparison` |
| DB 설정 | buffer pool 128MiB, redo log 64MiB, flush-at-commit=1, binary log OFF, REPEATABLE-READ |
| JDBC | `useServerPrepStmts=false`, 재작성 옵션만 A~C=false / D=true |
| 커넥션 풀 | 재작성 옵션별 2개 풀, 각 최대 2 / 최소 idle 1, 적재 동시성 1 |
| 입력 | 1천·1만·5만·10만 행, 첫 시트 A열, 헤더 없음, `00000`부터 연속된 문자열 키 |
| XLSX 형식 | shared strings. 앞선 기준선과 동일한 생성 방식과 파일 크기 |
| 반복 | 방식별 1만 행 워밍업 2회, 이후 크기·방식별 5회, 크기와 방식의 순서 순환 |
| 배치 크기 | 1,000으로 고정 |

동일 크기에 대해서는 네 방식에 같은 파일의 byte 배열을 전달한다. XLSX 생성 및 메모리 로딩, 테이블 초기화, 계측용 DB 조회, 결과 검증은 측정 시간에서 제외한다. HTTP 전송과 multipart 처리도 제외한다. 전체 웹 애플리케이션을 기동한 API 부하 테스트가 아니다.

JDBC 호출을 프록시로 계측하고, MySQL `performance_schema.events_statements_summary_by_digest`의 스키마별 INSERT 실행 횟수 증가량과 대조했다. 네트워크 패킷 수나 왕복 지연을 직접 측정한 결과는 아니다.

## 성능 결과

각 값은 5회 측정 중앙값이며 단위는 ms다. 워밍업과 정확성·롤백 확인은 성능 통계에서 제외한다.

### 전체 처리 시간: 트랜잭션 커밋 포함

| 행 수 | A. JPA | B. JDBC 단건 | C. JDBC 배치 | D. JDBC 배치 + 재작성 | D의 시간 감소율: A 대비 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 42.101 | 34.831 | 29.145 | 9.693 | 76.98% |
| 10,000 | 395.062 | 320.924 | 260.234 | 54.787 | 86.13% |
| 50,000 | 2,000.446 | 1,633.277 | 1,316.861 | 283.277 | 85.84% |
| 100,000 | 4,054.111 | 3,234.845 | 2,628.818 | 577.945 | 85.74% |

### 10만 행 구간별 시간과 실행 횟수

| 항목 | A. JPA | B. JDBC 단건 | C. JDBC 배치 | D. JDBC 배치 + 재작성 |
| --- | ---: | ---: | ---: | ---: |
| 파싱 중앙값 (ms) | 392 | 395 | 400 | 386 |
| 저장 + flush 중앙값 (ms) | 3,393 | 2,825 | 2,227 | 204 |
| 전체 최소~최대 (ms) | 4,012.184~4,059.277 | 3,189.515~3,464.458 | 2,600.304~2,788.769 | 558.210~607.228 |
| 처리량 (행/s) | 24,666 | 30,913 | 38,040 | 173,027 |
| INSERT PreparedStatement 생성 | 100,000 | 100,000 | 1 | 1 |
| JDBC 단건 실행 | 100,000 | 100,000 | 0 | 0 |
| JDBC addBatch 호출 | 0 | 0 | 100,000 | 100,000 |
| JDBC executeBatch 호출 | 0 | 0 | 100 | 100 |
| MySQL INSERT 실행 | 100,000 | 100,000 | 100,000 | 100 |
| 저장된 행 / 서로 다른 키 | 100,000 / 100,000 | 100,000 / 100,000 | 100,000 / 100,000 | 100,000 / 100,000 |
| 커밋 | 1 | 1 | 1 | 1 |

호출 횟수와 저장 결과는 해당 크기의 5회 실행에서 모두 같았다. 전체 시간에는 Workbook 닫기와 트랜잭션 종료 처리도 포함된다. 각 구간의 중앙값은 서로 다른 실행에서 나올 수 있으므로 파싱·저장 중앙값의 합이 전체 중앙값과 일치하지 않는다.

### 결과 해석

- A → B: 전체 시간이 20.21% 줄었다. JPA의 엔티티 관리, 생성 ID 처리, flush 및 트랜잭션 종료 경로 등이 함께 바뀌므로 이 차이를 순수한 단일 ORM 비용으로 단정하지 않는다. B는 생성된 ID를 엔티티에 채우지 않는다.
- B → C: PreparedStatement 생성은 10만 회에서 1회로 줄고 JDBC 배치는 100회 실행됐다. DB INSERT는 여전히 10만 회였다. 배치 호출 횟수가 줄었다고 서버의 SQL 실행 수까지 줄었다고 설명하면 안 된다.
- C → D: 배치 크기와 저장 코드는 같고 드라이버 옵션만 달라졌다. DB INSERT가 100회로 줄면서 저장 시간이 2,227ms에서 204ms로 줄었다. 이번 비교에서 가장 큰 추가 개선이 발생한 구간이다.
- D에서는 파싱 중앙값이 저장 시간보다 크다. 저장 최적화 후에는 상대적으로 엑셀 파싱의 비중이 커졌지만, 파서 최적화는 이번 실험 범위에 포함하지 않았다.

## 정확성과 전체 롤백 검증

워밍업 8회와 본 측정 80회에서 DB의 모든 행을 키 순서대로 조회해 입력과 대조했다. 단순 COUNT 비교에 그치지 않고 각 키의 정확한 값, level=null, 양수 ID를 확인했다. 예상 건수 이후의 추가 행이 없는지도 검사했다.

별도로 2,501행을 넣어 마지막 배치가 501행인 경우를 검증했다. C와 D 모두 `executeBatch()` 3회, 2,501개 키 일치, 커밋 1회였다.

롤백 검증에서는 먼저 기존 데이터 `id=1, key=ZZZZZ, level=7`을 넣고, 임시 CHECK 제약 조건으로 `01500` 키만 거부했다. 정상적인 5자리 키이므로 파싱을 통과하고, 적재 중 두 번째 배치에서 MySQL 오류 3819가 발생한다. 성능 측정에는 이 제약 조건이 없다.

| 방식 | 오류 전 완료된 쓰기 | 커밋 | 롤백 | 검증 후 결과 |
| --- | --- | ---: | ---: | --- |
| A. JPA | 단건 INSERT 1,500회 | 0 | 1 | 기존 데이터 1행만 보존 |
| B. JDBC 단건 | 단건 INSERT 1,500회 | 0 | 1 | 기존 데이터 1행만 보존 |
| C. JDBC 배치 | 첫 1,000행 배치 완료, 두 번째 배치 실패 | 0 | 1 | 기존 데이터 1행만 보존 |
| D. JDBC 배치 + 재작성 | 첫 1,000행 배치 완료, 두 번째 배치 실패 | 0 | 1 | 기존 데이터 1행만 보존 |

배치 실패 시 드라이버 내부의 개별 행 처리 결과까지 동일하다고 주장하지 않는다. 검증한 것은 이전 배치를 포함한 해당 요청의 쓰기가 모두 취소되고, 이미 존재하던 데이터는 유지된다는 점이다. AUTO_INCREMENT 번호의 연속성 복원은 보장하거나 검증하지 않았다.

## 실제 반영 시 고려할 차이와 한계

현재 적재 서비스는 생성된 엔티티의 ID나 영속 상태를 반환하지 않고 건수와 소요 시간만 반환한다. 이 경로에서는 JDBC로 DB에 생성한 ID를 엔티티에 채우지 않아도 결과를 동일하게 유지할 수 있다. 하지만 이 실험용 어댑터를 일반적인 JPA 저장소 대체품으로 사용할 수는 없다. JDBC는 JPA의 엔티티 생명주기 콜백, 자동 변경 감지, 연관관계 저장 등을 대신하지 않으며 SQL·컬럼 매핑을 직접 관리해야 한다.

실제 적용 방향은 엑셀 적재용 JDBC 저장 컴포넌트에 책임을 한정하고, 현재의 트랜잭션 경계를 유지하는 것이다. 배치 크기 1,000은 비교를 위한 초기값이며 최적값으로 확정하지 않았다. 후속 작업에서는 100·500·1,000 비교 또는 실제 API 반영 중 필요한 범위를 선택할 수 있다.

이후 [배치 크기 비교 실험](excel-batch-size.md)에서 1만·10만 행, 로컬 동시 적재 1건을 대상으로 100~5,000을 비교했다. 해당 결과와 초기값 선택 근거는 별도 보고서에 기록했다.

이번 결과는 한 JVM과 로컬 DB에서 얻은 것으로, 운영 네트워크 지연·동시 업로드·커넥션 대기·HTTP 전송 시간은 포함하지 않는다. 측정 순서는 순환했지만 다른 데스크톱 프로세스 부하는 통제하지 않았다. JIT·GC·계측 오버헤드가 포함되며, 표본 5개로 p95나 운영 SLA를 주장하지 않는다. 작은 입력의 일부 첫 측정에는 비교적 큰 편차가 있어 최소·최대를 원시 자료와 집계에 남겼다.

앞선 기준선에서 발견한 inline string 셀 변환 문제는 그대로 남아 있다. 이번 실험은 검증된 shared string 입력에 대한 결과다. 별도의 정확성 수정이 필요하다. [앞선 분석과 기준선](excel-import-baseline.md)

## 재현 및 증거 자료

- [실험 코드](benchmarks/excel-import-comparison/ExcelImportComparison.java)
- [실행 스크립트](benchmarks/excel-import-comparison/run.sh): 매번 새 임시 DB 생성, 종료 시 해당 DB 서버 정리
- [집계·검증 스크립트](benchmarks/excel-import-comparison/summarize.py)
- [원시 측정 88회](benchmarks/excel-import-comparison/comparison.csv), [중앙값·최소·최대 집계](benchmarks/excel-import-comparison/summary.csv)
- [마지막 부분 배치 검증](benchmarks/excel-import-comparison/correctness.csv), [롤백 검증](benchmarks/excel-import-comparison/rollback.csv)
- [측정 환경](benchmarks/excel-import-comparison/environment.txt), [기준 커밋](benchmarks/excel-import-comparison/source-commit.txt)
- [실행한 코드 해시](benchmarks/excel-import-comparison/harness-sha256.txt), [입력 해시](benchmarks/excel-import-comparison/input-sha256.txt), [INSERT 형태](benchmarks/excel-import-comparison/insert-sql.txt)

저장소 루트에서 Java 17, Homebrew MySQL 8.0, 비어 있는 13318 포트를 준비하고 실행한다. Gradle 의존성이 로컬 캐시에 있어야 한다. 캐시가 없다면 먼저 프로젝트의 Gradle 의존성을 받아야 한다. 운영 DB 주소는 사용하지 않는다.

JDBC 리팩터링 이후에는 [역사적 실험 재현 안내](benchmarks/README.md)에 따라 기준 커밋의 별도 checkout에서 실행한다.
이 보고서의 '애플리케이션 변경 없음'은 실험 당시의 범위 설명이며, 이후 실제 JDBC 구현과 구분한다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  bash backend/docs/benchmarks/excel-import-comparison/run.sh

# 실행 마지막에 출력된 임시 경로의 results를 사용한다.
python3 backend/docs/benchmarks/excel-import-comparison/summarize.py \
  /private/tmp/passtival-excel-comparison.XXXXXX/results

# 저장된 이번 측정 자료를 다시 검증하고 집계한다.
python3 backend/docs/benchmarks/excel-import-comparison/summarize.py \
  backend/docs/benchmarks/excel-import-comparison
```

XLSX 생성과 byte 배열 로딩은 타이머 밖에서 수행한다. 재생성한 XLSX의 ZIP 메타데이터에 따라 파일 해시는 달라질 수 있다. 임시 경로의 파일과 DB 데이터는 재현 확인을 위해 남으며, MySQL 프로세스는 실험 종료 시 중지한다.
