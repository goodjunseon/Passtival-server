# 인증키 엑셀 적재: 원인 분석과 기준 성능 측정

측정일: 2026-09-24 (Asia/Seoul)

측정 대상 커밋: `2799a43dd1ff3bf0803b9c2095cec47a87079277`

## 범위와 결론

문제 후보 1번인 `saveAll()`과 `IDENTITY` 조합의 저장 동작을 조사했다. 기존 애플리케이션의 서비스·엔티티·설정은 변경하지 않았다. 이 문서는 원인 분석과 기준선 기록이며, 개선 구현이나 개선 효과를 주장하는 포트폴리오가 아니다.

현재 경로에서는 유효한 인증키 N개를 저장할 때 INSERT가 N회 실행된다. JDBC `addBatch()`와 `executeBatch()`는 호출되지 않는다. 요청당 트랜잭션 커밋은 1회다.

통제된 로컬 환경에서 10만 행 적재의 전체 소요 시간 중앙값은 3.979초였고, 저장·flush 구간 중앙값은 3.355초였다. 각 실행의 저장 구간 비중을 계산한 중앙값은 84.14%였다. 현재 측정에서는 DB 저장 경로가 주된 개선 후보로 확인됐다.

이 결과만으로 운영 장애나 서비스 요구 성능 미달을 확정할 수는 없다. 운영 DB와의 네트워크 지연, 동시 요청, 목표 처리 시간을 포함한 검증은 아직 하지 않았다.

## 코드 및 실행 동작 확인

- [AuthenticationKeyImportService](../src/main/java/com/passtival/backend/domain/authenticationkey/service/AuthenticationKeyImportService.java)는 파싱한 엔티티를 리스트에 모아 `saveAll()`과 `flush()`를 호출한다.
- [AuthenticationKey](../src/main/java/com/passtival/backend/domain/authenticationkey/model/AuthenticationKey.java)의 ID는 `GenerationType.IDENTITY`다. 새로 만드는 엔티티의 ID는 `null`이다.
- 설치된 Spring Data JPA 3.4.5의 `SimpleJpaRepository` 바이트코드를 확인했다. `saveAll()`은 전달된 엔티티를 순회하면서 각각 `save()`를 호출한다.
- Hibernate의 IDENTITY INSERT는 생성된 ID를 얻어야 하며, 해당 엔티티의 INSERT에 JDBC 배치를 적용하지 않는다. [Hibernate 6.6 공식 문서](https://docs.hibernate.org/orm/6.6/userguide/html_single/#batch-session-batch)

계측된 SQL 형태는 다음과 같다. 값은 PreparedStatement 파라미터로 바인딩된다.

```sql
insert into authentication_key (authentication_key,level) values (?,?)
```

`saveAll()`의 Java 메서드 호출 횟수, JDBC 배치 실행 횟수, DB가 실행한 INSERT 수, 트랜잭션 커밋 횟수는 서로 다른 지표다. 이 측정에서 커밋이 N번 발생했다는 설명은 틀리다.

### 배치 크기 설정만으로 해결되는지에 대한 진단

애플리케이션 설정 파일은 그대로 두고, 별도 측정 프로세스의 Hibernate 설정만 `hibernate.jdbc.batch_size=50`으로 바꿔 1,000행을 적재했다.

| 설정 | 행 수 | JDBC INSERT 실행 | addBatch | executeBatch | MySQL INSERT 실행 | 커밋 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 기본 기준선: batch_size=0 | 1,000 | 1,000 | 0 | 0 | 1,000 | 1 |
| 진단: batch_size=50 | 1,000 | 1,000 | 0 | 0 | 1,000 | 1 |

배치 크기 설정을 추가해도 IDENTITY 엔티티의 INSERT가 배치로 전환되지 않음을 확인했다. 진단 실행은 워밍업 없이 SQL 호출 형태만 확인한 것이므로, 이 실행의 시간은 기준선과 성능 비교하지 않는다.

Connector/J의 `rewriteBatchedStatements`도 `executeBatch()`로 전달된 INSERT 등을 재작성하는 옵션이다. 현재 측정에서는 `executeBatch()` 호출 자체가 없었다. 이 옵션만의 효과는 이번에 측정하지 않았다. [MySQL Connector/J 공식 문서](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-performance-extensions.html)

## 측정 환경과 방법

| 항목 | 조건 |
| --- | --- |
| 장비 | Apple M3, 논리 CPU 8개, 메모리 16GiB |
| OS / JVM | macOS 26.6.2 arm64 / OpenJDK 17.0.20 |
| JVM 힙 | `-Xms512m -Xmx1024m` |
| 라이브러리 | Spring Boot 3.4.5 / Spring Data JPA 3.4.5 / Hibernate 6.6.13.Final / Apache POI 5.2.5 |
| DB / 드라이버 | MySQL 8.0.46 / Connector/J 9.1.0 |
| 접속 | 같은 장비의 `127.0.0.1:13317`, 임시 데이터 디렉터리의 전용 DB |
| 주요 설정 | `hibernate.jdbc.batch_size=0`, `rewriteBatchedStatements=false`, `useServerPrepStmts=false` |
| DB 설정 | InnoDB, buffer pool 128MiB, `innodb_flush_log_at_trx_commit=1`, binary log OFF |
| 테이블 | ID AUTO_INCREMENT PK, 인증키 VARCHAR(5) NOT NULL, level nullable, 인증키 UNIQUE 없음 |
| 풀 / 동시성 | HikariCP 최대 2 / 최소 idle 1, import 동시 실행 1개 |
| 입력 | 첫 시트 A열, 헤더 없음, 문자열 `00000`부터 순서대로 생성, 중복 없는 유효한 5자리 키 |
| 파일 크기 | 1천 행 13,457B / 1만 행 103,483B / 5만 행 515,547B / 10만 행 1,029,797B |
| 반복 | 1만 행 워밍업 2회 후, 각 크기 5회. 라운드마다 크기별 실행 순서를 순환 |

실제 `AuthenticationKeyImportService`와 `AuthenticationKeyRepository`를 불러오고, Spring의 `JpaTransactionManager`와 애너테이션 기반 트랜잭션 프록시를 적용했다. 저장소를 Mock으로 대체하지 않았다. 전체 웹 애플리케이션을 띄우지 않고 적재 서비스 경로만 측정했다.

파일 생성과 byte 배열 로딩은 측정 전에 마쳤다. 업로드가 끝난 입력에 해당하는 `MockMultipartFile`로 실제 POI 파서에 전달했다. HTTP 전송, multipart 요청 처리, 업로드 임시 파일의 디스크 I/O는 측정에서 제외했다.

각 실행 전에 전용 테이블을 비웠다. 측정이 끝난 후 실제 DB에서 전체 건수와 서로 다른 키의 개수가 모두 입력 행 수와 같은지, level이 모두 null인지 확인했다. 이 확인 쿼리와 데이터 초기화는 측정 시간에 포함하지 않았다. 모든 워밍업·본 측정·진단 실행에서 저장 건수 검증을 통과했다.

JDBC 계측 프록시로 INSERT용 PreparedStatement 생성·실행, `addBatch()`, `executeBatch()`, `Connection.commit()` 횟수를 세었다. 추가로 MySQL `performance_schema.events_statements_summary_by_digest`의 해당 스키마 INSERT 실행 횟수 증가량과 대조했다. 아래 INSERT 수는 SQL 실행 수이며, 네트워크 패킷 수를 측정한 것은 아니다.

## 기준 성능 결과

시간은 5회 측정의 중앙값이며, 단위는 ms다. 전체 최소·최대는 각 크기의 실제 관측값이다.

| 행 수 | 파싱 | 저장 + flush | 전체: 커밋 포함 | 전체 최소 ~ 최대 | 저장 비중 중앙값 | 전체 처리량 중앙값 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 6 | 45 | 56.384 | 42.142 ~ 65.694 | 79.71% | 17,736행/s |
| 10,000 | 43 | 333 | 401.238 | 394.611 ~ 533.907 | 82.49% | 24,923행/s |
| 50,000 | 194 | 1,681 | 1,995.097 | 1,938.839 ~ 2,479.736 | 84.07% | 25,061행/s |
| 100,000 | 403 | 3,355 | 3,979.255 | 3,943.291 ~ 4,337.939 | 84.14% | 25,130행/s |

| 행 수 | JDBC INSERT 실행 | JDBC addBatch / executeBatch | MySQL INSERT 실행 | 실제 저장 / 서로 다른 키 | 커밋 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 1,000 | 0 / 0 | 1,000 | 1,000 / 1,000 | 1 |
| 10,000 | 10,000 | 0 / 0 | 10,000 | 10,000 / 10,000 | 1 |
| 50,000 | 50,000 | 0 / 0 | 50,000 | 50,000 / 50,000 | 1 |
| 100,000 | 100,000 | 0 / 0 | 100,000 | 100,000 / 100,000 | 1 |

호출·저장 횟수는 해당 크기의 5회 측정에서 모두 같았다.

- 파싱 시간은 Workbook 열기와 엔티티 리스트 생성까지다.
- 저장 시간은 `saveAll()` 및 명시적 `flush()`를 포함하고 트랜잭션 커밋은 제외한다.
- 전체 시간은 트랜잭션 프록시 호출부터 커밋 완료 후 반환까지다. Workbook 닫기, 트랜잭션 처리 등 나머지 비용도 포함하므로 파싱·저장 시간의 합과 일치하지 않는다. 각 열의 중앙값도 서로 다른 실행에서 나올 수 있다.
- JDBC `commit()` 자체의 중앙값은 10만 행에서 0.248ms였다. 이는 Spring/Hibernate가 커밋 전후에 수행하는 전체 작업 시간이 아니다.
- 10만 행에서 전체 구간 GC 누적 시간 중앙값은 176ms였다. 저장 구간에는 ORM 처리, DB 통신, DB 실행, 해당 구간 GC 등이 함께 들어 있으므로 3.355초 전부를 네트워크 지연으로 설명할 수 없다.

## 입력 형식에 관한 별도 발견

처음 생성한 XLSX는 SXSSF 기본값인 inline string 형식이었다. POI 5.2.5에서 해당 파일의 문자열 셀에 현재 코드의 `setCellType(CellType.STRING)`을 적용하면 `00000`이 빈 문자열로 바뀌는 현상을 재현했다. 초기 워밍업에서 1만 행이 모두 제외되어, 저장 건수 검증이 이 실행을 실패로 처리했다. 이 시간은 기준 성능 통계에서 제외했다.

기준선 입력은 기존 API 테스트의 XSSFWorkbook 파일과 같은 shared string 형식으로 생성했다. 애플리케이션 파서는 수정하지 않았다. 모든 XLSX 문자열이 정상 처리된다고 일반화할 수 없으며, 이 문제는 앞서 정리한 입력 정확성 후보에 해당한다. [관측 기록](benchmarks/excel-import-baseline/inline-string-observation.txt)

## 해석의 범위

확인된 것은 **N건의 단건 INSERT와 배치 미사용**, 그리고 **이 환경에서 저장 구간이 전체의 약 80~84%를 차지한다는 사실**이다. 개선 대안을 고르기 위한 기준선으로 사용할 수 있다.

아직 확인하지 않은 사항은 운영 DB 환경의 처리 시간, 네트워크 지연의 실제 비중, 다중 요청 시 커넥션 대기, 특정 개선 방식의 속도와 정합성이다. 현재 운영용 `DB_URL` 옵션이나 DB 인덱스를 조회한 결과가 아니라, 명시한 설정과 엔티티 매핑을 기준으로 구성한 로컬 실험이다.

1개 JVM에서 각 크기를 5번 측정했으며, 별도 JVM을 반복 실행한 통계가 아니다. 다른 데스크톱 프로세스의 부하를 통제하지 않았고 계측 오버헤드도 제거하지 않았다. 워밍업과 실행 순서를 명시하고 원시 결과를 함께 남겼다. 표본이 적어 p95나 운영 SLA를 주장하지 않는다.

이번에는 저장 방식 교체, ID 전략 변경, 배치 옵션의 애플리케이션 반영을 하지 않았다.

후속으로 [JPA와 JDBC 저장 방식 비교 실험](excel-import-comparison.md)을 수행했다. 해당 보고서의 개선율은 후속 실험에서 함께 재측정한 JPA 값을 기준으로 하며, 이 문서의 기준선 숫자와 구분한다.

## 재현 자료

- [원시 측정 CSV](benchmarks/excel-import-baseline/baseline.csv): 워밍업 2회와 본 측정 20회
- [집계 CSV](benchmarks/excel-import-baseline/summary.csv)
- [환경·테이블 DDL](benchmarks/excel-import-baseline/environment.txt)
- [batch_size=50 진단 결과](benchmarks/excel-import-baseline/identity-batch-size-50.csv) 및 [진단 환경](benchmarks/excel-import-baseline/identity-batch-size-50-environment.txt)
- [실제 SQL 형태](benchmarks/excel-import-baseline/insert-sql.txt)
- [측정용 Java 실행 파일](benchmarks/excel-import-baseline/ExcelImportBaseline.java) 및 [Gradle classpath 추출 스크립트](benchmarks/excel-import-baseline/classpath.init.gradle)
- [입력 파일 SHA-256](benchmarks/excel-import-baseline/input-sha256.txt): 측정 당시 파일 식별용. 다시 생성하면 ZIP 메타데이터 등으로 해시가 달라질 수 있다.

### 같은 macOS 환경에서 재현

JDBC 리팩터링 이후에는 먼저 [역사적 실험 재현 안내](benchmarks/README.md)에 따라 기준 커밋의 별도 checkout을 준비한다.
아래 명령은 그 checkout에서 실행한다. 본 보고서의 '현재 경로'는 측정 당시 JPA 구현을 가리킨다.

아래 명령은 `backend` 디렉터리에서 실행한다. Homebrew MySQL 8.0이 설치되어 있어야 하며, 포트 13317은 비어 있어야 한다. 측정 프로그램은 해당 포트의 `passtival_excel_baseline.authentication_key` 테이블을 매 실행마다 비우므로, 반드시 아래처럼 새로 만든 임시 DB를 사용한다. 기본 설정 파일과 기존 DB 데이터 디렉터리는 사용하지 않는다.

```bash
baseline_dir=$(mktemp -d /private/tmp/passtival-excel-baseline.XXXXXX)
mysql_bin=/opt/homebrew/opt/mysql@8.0/bin

"$mysql_bin/mysqld" --no-defaults --initialize-insecure \
  --datadir="$baseline_dir/data" --log-error="$baseline_dir/mysql-init.log" \
  --innodb-buffer-pool-size=128M

"$mysql_bin/mysqld" --no-defaults --datadir="$baseline_dir/data" \
  --bind-address=127.0.0.1 --port=13317 --mysqlx=0 \
  --socket="$baseline_dir/mysql.sock" --pid-file="$baseline_dir/mysql.pid" \
  --log-error="$baseline_dir/mysql.log" --daemonize \
  --innodb-buffer-pool-size=128M --innodb-redo-log-capacity=64M \
  --innodb-flush-log-at-trx-commit=1 --performance-schema=ON --skip-log-bin

"$mysql_bin/mysql" --no-defaults --socket="$baseline_dir/mysql.sock" --user=root \
  --execute='CREATE DATABASE passtival_excel_baseline CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'

./gradlew --offline -I docs/benchmarks/excel-import-baseline/classpath.init.gradle \
  -Dexcel.baseline.classpathFile="$baseline_dir/classpath.txt" exportExcelBaselineClasspath

baseline_classpath=$(cat "$baseline_dir/classpath.txt")
javac -cp "$baseline_classpath" -d "$baseline_dir/classes" \
  docs/benchmarks/excel-import-baseline/ExcelImportBaseline.java

java -Xms512m -Xmx1024m -cp "$baseline_dir/classes:$baseline_classpath" \
  ExcelImportBaseline "$baseline_dir/results"

# 호출 형태만 확인하는 별도 진단. 워밍업이 없으므로 실행 시간은 위 결과와 비교하지 않는다.
java -Xms512m -Xmx1024m -Dbaseline.diagnostic=true -Dbaseline.hibernateBatchSize=50 \
  -cp "$baseline_dir/classes:$baseline_classpath" \
  ExcelImportBaseline "$baseline_dir/identity-batch-size-50"

"$mysql_bin/mysqladmin" --no-defaults --socket="$baseline_dir/mysql.sock" --user=root shutdown
```

원시 측정 파일과 생성한 XLSX는 `baseline_dir` 아래에 남는다. 이 문서의 숫자는 위에 기록한 커밋을 대상으로 한 것이므로, 서비스 구현이 바뀐 뒤 재실행한 결과와 구분해야 한다.
