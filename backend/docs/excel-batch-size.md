# 인증키 엑셀 적재: JDBC 배치 크기 비교

측정일: 2026-09-24 (Asia/Seoul)

애플리케이션 기준 커밋: `2799a43dd1ff3bf0803b9c2095cec47a87079277`

## 판단

**현재 로컬 환경에서 1만·10만 행을 한 번에 한 파일씩 적재한다면, 도입 초기 배치 크기로 1,000을 추천한다.** 100 → 500에서 가장 큰 개선이 나타났고, 500 → 1,000까지 추가 이점이 있었다. 1,000 → 2,000의 추가 절약 시간은 작았으며, 더 큰 후보가 두 입력 크기에서 일관되게 유리하지 않았다.

이 판단은 모든 환경의 최적값을 찾았다는 뜻이 아니다. 사용자가 정한 **개선 효과가 둔해지는 구간**을 찾은 결과다. 10만 행만 보면 5,000의 저장 시간 중앙값이 가장 낮았으므로 이 결과도 함께 제시한다. 1,000~2,000에서 효과가 둔해지는 점과 반복 편차를 고려해 1,000을 초기값으로 선택할 수 있다.

이번에는 실험 코드와 자료를 추가했다. 애플리케이션 API·서비스·설정의 실제 저장 방식은 변경하지 않았다.

## 사용자 조건과 측정 방법

| 항목 | 조건 |
| --- | --- |
| 입력 | 평소 1만 행 / 최대 10만 행 |
| 동시 적재 | 관리자 업로드 1건 |
| 환경 | 기존 로컬 장비, 별도의 임시 MySQL |
| 선택 기준 | 배치 크기를 늘렸을 때 추가 개선이 둔해지는 구간 |
| 후보 | 100, 500, 1,000, 2,000, 5,000 |
| 고정 조건 | JDBC 배치 + rewriteBatchedStatements=true, 같은 파서·SQL·테이블·파일 |
| 트랜잭션 | 파일당 1개, 배치 단위 커밋 없음 |
| 반복 | JVM 3개를 순차 실행, 각 입력 크기·후보를 JVM당 10회, 조합당 총 30회 |
| 워밍업 | JVM마다 후보별 10만 행 2회, 총 30회 |

기존 `AuthenticationKeyImportService`를 그대로 호출하고, 실험용 저장소 어댑터의 `JdbcTemplate.batchUpdate()` 배치 크기만 바꿨다. 모든 후보에서 같은 `JpaTransactionManager`와 DataSource를 사용했다. 각 라운드에서 입력 크기와 후보 순서를 순환·역순으로 바꿨다. JVM은 새로 시작했으며 DB 서버는 실험 동안 유지했다. 테이블은 매번 측정 전에 비웠다.

같은 XLSX 파일을 세 JVM에서 재사용하고 SHA-256이 같은지 확인했다. 첫 시트 A열에 `00000`부터 시작하는 중복 없는 5자리 문자열을 사용한다. 이전 실험의 inline string 변환 문제를 피하기 위해 shared string 형식의 검증된 입력을 유지했다.

주지표인 **저장·커밋 시간**은 `JdbcTemplate.batchUpdate()` 호출 시간과 JDBC `Connection.commit()` 호출 시간의 합이다. 나노초 시계로 측정해 ms로 기록했다. Spring의 모든 트랜잭션 종료 처리를 포함하는 지표는 아니므로, 커밋 후 서비스 반환까지의 **전체 시간**도 별도로 기록했다. 파싱·파일 생성·HTTP 전송과 혼동하지 않는다.

파일 생성과 메모리 로딩, 테이블 초기화, DB 결과 확인은 타이머 밖에서 수행한다. HTTP 업로드 및 multipart 처리는 측정에 포함하지 않는다. 원래 서비스의 INFO 로그와 JDBC 계측 오버헤드는 포함한다.

## 결과

시간은 조합당 30회 중앙값이다. 단위는 ms이며, 각 구간의 중앙값은 서로 다른 실행에서 나올 수 있다.

| 배치 크기 | 1만 행 저장·커밋 | 10만 행 저장·커밋 | 1만 행 전체 | 10만 행 전체 | 10만 행 DB INSERT 수 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 27.109 | 241.213 | 63.752 | 643.437 | 1,000 |
| 500 | 20.027 | 197.827 | 57.834 | 605.039 | 200 |
| 1,000 | 19.139 | 187.350 | 55.935 | 590.053 | 100 |
| 2,000 | 18.856 | 185.659 | 56.904 | 592.046 | 50 |
| 5,000 | 19.832 | 177.897 | 57.118 | 599.375 | 20 |

### 추가 개선이 둔해지는 지점

단순히 서로 다른 실행의 중앙값을 빼는 방법과 별도로, **같은 JVM·라운드에 실행한 인접 후보끼리** 절약 시간과 비율을 계산했다. 아래 값은 30쌍의 중앙값이다. 음수는 더 큰 배치가 느렸다는 뜻이다.

| 배치 변경 | 1만 행 절약 시간 | 1만 행 개선율 | 10만 행 절약 시간 | 10만 행 개선율 |
| --- | ---: | ---: | ---: | ---: |
| 100 → 500 | 6.854ms | 25.708% | 34.351ms | 14.718% |
| 500 → 1,000 | 1.018ms | 5.058% | 11.506ms | 6.205% |
| 1,000 → 2,000 | 0.346ms | 1.810% | 3.490ms | 2.059% |
| 2,000 → 5,000 | -0.952ms | -5.149% | -1.183ms | -0.609% |

10만 행에서 2,000 → 5,000은 전체 중앙값만 비교하면 빨라지지만, 같은 라운드끼리 비교한 차이의 중앙값은 반대 방향이다. `median(A) - median(B)`와 `median(A - B)`는 서로 다른 통계량이므로 이런 결과가 가능하다. 큰 편차가 있는 상황에서 하나의 숫자만 보고 뚜렷한 개선이라고 단정하지 않았다.

1,000 → 2,000의 10만 행 개선율은 JVM별 쌍 비교 중앙값이 **-6.238%, +8.669%, +0.383%**로 방향과 크기가 달랐다. 2,000 → 5,000도 **+8.050%, -4.096%, +5.515%**로 일관되지 않았다. 반면 1만 행은 2,000 → 5,000의 쌍 비교가 세 JVM에서 모두 느려지는 방향이었다.

상한인 5,000까지 크기를 늘렸을 때 일관된 추가 이점이 관찰되지 않아 10,000 이상으로 탐색을 확장하지 않았다. 실제 최솟값의 존재나 그 위치를 증명한 결과는 아니다.

### 측정 편차

저장·커밋 시간의 가운데 50% 구간(IQR, 25~75백분위)이다. 중앙값의 신뢰구간을 뜻하지 않는다.

| 배치 크기 | 1만 행 IQR (ms) | 10만 행 IQR (ms) |
| ---: | ---: | ---: |
| 100 | 26.712~28.294 | 223.369~254.904 |
| 500 | 19.789~20.967 | 180.423~219.396 |
| 1,000 | 18.709~20.521 | 173.360~207.625 |
| 2,000 | 18.510~20.870 | 166.086~199.447 |
| 5,000 | 19.143~20.279 | 171.628~193.903 |

1,000 이후에는 분포가 많이 겹친다. IQR이 겹친다는 사실만으로 차이가 없다고 증명할 수는 없지만, 적은 추가 절약 시간과 JVM별 결과의 변동을 함께 보면 특정 큰 배치의 우위를 확정하기 어렵다.

참고로 JVM과 쌍을 이룬 라운드를 재표집한 5,000회 부트스트랩에서, 10만 행 1,000 → 2,000의 개선율 2.5~97.5백분위 구간은 **-13.155~9.276%**, 2,000 → 5,000은 **-7.918~11.606%**였다. JVM 그룹이 3개뿐인 참고 통계이며 운영 환경에 대한 신뢰 보장이나 보편적인 통계적 유의성 주장에 사용하지 않는다.

## 메모리와 GC

| 배치 크기 | 10만 행 저장 구간 누적 할당량 중앙값 | 전체 GC 시간 중앙값 |
| ---: | ---: | ---: |
| 100 | 107.470MiB | 70.5ms |
| 500 | 103.597MiB | 68ms |
| 1,000 | 103.121MiB | 68ms |
| 2,000 | 102.895MiB | 73ms |
| 5,000 | 102.790MiB | 65ms |

메인 스레드에서 할당한 바이트를 측정한 것으로, 동시에 살아 있는 객체 크기나 필요한 최소 힙 크기가 아니다. 이 누적 할당량은 큰 배치에서 단조 증가하지 않았다. 따라서 '배치가 크면 이번 실험에서 메모리를 더 많이 썼다'고 설명할 근거는 없다.

10ms 주기로 관측한 전체 JVM 힙 최고값의 중앙값은 10만 행 후보별 약 661~701MiB였다. 파싱 객체와 직전 실행에서 남은 객체, GC 시점도 포함하며 배치 버퍼 크기를 뜻하지 않는다. 순간적인 최고값을 놓칠 수 있으므로 참고 지표로만 남겼다. 명시적인 `System.gc()` 호출은 하지 않았다.

## 정확성 및 롤백

- 워밍업 30회와 본 측정 300회에서 모든 키의 정확한 값, level=null, 양수 DB 생성 ID, 추가 행 없음, 커밋 1회를 확인했다.
- JDBC INSERT 준비는 요청당 1회, `addBatch()`는 행 수만큼 실행됐다. `executeBatch()`와 MySQL INSERT 수는 각각 `ceil(행 수 / 배치 크기)`와 일치했다.
- 후보별로 `2 × 배치 크기 + 1`행을 넣어 마지막 1행이 빠지지 않는지 확인했다. JVM별 5개씩 총 15건에서 배치 실행 3회와 정확한 저장 결과를 확인했다.
- 같은 입력의 두 번째 배치 중간 키를 임시 CHECK 제약으로 거부했다. 15건 모두 첫 배치가 완료된 뒤 두 번째 배치가 실패했고, 커밋 0회·롤백 1회였다. 요청에서 넣은 행은 모두 취소되고 기존 `ZZZZZ / level=7 / id=1` 행만 보존됐다.

성능 측정 시에는 오류 주입용 CHECK 제약이 없다. 실패 때 AUTO_INCREMENT 번호까지 연속적으로 복원되는지는 검증하거나 보장하지 않는다.

## 환경과 해석 범위

이전 실험과 같은 Apple M3 / 16GiB 장비에서 실행했다. macOS 26.6.2 arm64, OpenJDK 17.0.20, JVM 힙 `-Xms512m -Xmx1024m`이다. Spring 6.2.6, Hibernate 6.6.13.Final, POI 5.2.5, Connector/J 9.1.0, MySQL 8.0.46을 사용했다.

DB는 `127.0.0.1:13319`의 전용 임시 인스턴스다. buffer pool 128MiB, redo log 64MiB, `innodb_flush_log_at_trx_commit=1`, binary log OFF, REPEATABLE-READ, `max_allowed_packet=64MiB`다. 클라이언트 준비 구문(`useServerPrepStmts=false`)과 재작성 옵션을 사용하고, Hikari 풀은 최대 2 / 최소 idle 1로 유지했다. 운영 DB에는 접속하지 않았고 임시 MySQL은 측정 후 종료했다.

원시 파일에 JVM별 환경·설정·입력 해시를 보존했다. 측정 순서를 분산했지만 다른 데스크톱 프로세스, GC 시점, DB 캐시의 영향을 제거하지는 못한다. 커넥션 풀 대기, 원격 DB 네트워크, 동시 업로드, HTTP 전달 시간, UNIQUE 제약을 추가한 스키마 등은 이번 결론의 적용 범위에 포함하지 않는다. 배치 크기를 바꾸어도 기존 XSSFWorkbook 전체 로딩과 inline string 처리 문제는 해결되지 않는다.

## 재현 자료

- [측정 조건과 판단 계획](benchmarks/excel-batch-size/experiment-plan.md)
- [측정용 Java 코드](benchmarks/excel-batch-size/ExcelBatchSizeBenchmark.java), [실행 스크립트](benchmarks/excel-batch-size/run.sh)
- [집계·검증 코드](benchmarks/excel-batch-size/summarize.py)
- [전체 집계](benchmarks/excel-batch-size/results/summary.csv), [JVM별 집계](benchmarks/excel-batch-size/results/per-fork-summary.csv), [인접 후보 추가 개선](benchmarks/excel-batch-size/results/marginal-gains.csv)
- 원시 측정: [JVM 1](benchmarks/excel-batch-size/results/fork-1/measurements.csv), [JVM 2](benchmarks/excel-batch-size/results/fork-2/measurements.csv), [JVM 3](benchmarks/excel-batch-size/results/fork-3/measurements.csv)
- 롤백 확인: [JVM 1](benchmarks/excel-batch-size/results/fork-1/rollback.csv), [JVM 2](benchmarks/excel-batch-size/results/fork-2/rollback.csv), [JVM 3](benchmarks/excel-batch-size/results/fork-3/rollback.csv)
- 마지막 배치 확인: [JVM 1](benchmarks/excel-batch-size/results/fork-1/correctness.csv), [JVM 2](benchmarks/excel-batch-size/results/fork-2/correctness.csv), [JVM 3](benchmarks/excel-batch-size/results/fork-3/correctness.csv)
- 환경: [JVM 1](benchmarks/excel-batch-size/results/fork-1/environment.txt), [JVM 2](benchmarks/excel-batch-size/results/fork-2/environment.txt), [JVM 3](benchmarks/excel-batch-size/results/fork-3/environment.txt)
- [기준 커밋](benchmarks/excel-batch-size/results/source-commit.txt), [실행 코드 해시](benchmarks/excel-batch-size/results/harness-sha256.txt)

저장소 루트에서 Java 17, Homebrew MySQL 8.0, 비어 있는 13319 포트를 준비한다. Gradle 의존성은 로컬 캐시에 있어야 한다.

JDBC 리팩터링 이후에는 [역사적 실험 재현 안내](benchmarks/README.md)에 따라 기준 커밋의 별도 checkout에서 실행한다.
이 보고서의 '애플리케이션 변경 없음'은 실험 당시의 범위 설명이며, 이후 실제 JDBC 구현과 구분한다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  bash backend/docs/benchmarks/excel-batch-size/run.sh

# 실행 마지막에 출력된 임시 경로의 results를 집계한다.
python3 backend/docs/benchmarks/excel-batch-size/summarize.py \
  /private/tmp/passtival-excel-batch.XXXXXX/results

# 보존된 이번 측정 자료를 다시 검증하고 집계한다.
python3 backend/docs/benchmarks/excel-batch-size/summarize.py \
  backend/docs/benchmarks/excel-batch-size/results
```

스크립트는 새 임시 DB를 만들고 종료 시 서버를 중지한다. 생성한 XLSX·DB 데이터·실행 로그는 출력된 임시 디렉터리에 남긴다. `BATCH_CANDIDATES` 환경 변수로 후보를 바꿀 수 있지만, 다른 후보로 측정한 결과는 이번 자료와 별도로 기록해야 한다.
