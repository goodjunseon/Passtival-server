# 인증키 엑셀 업로드 저장 방식 비교

2026년 9월 24일 로컬 실험 기록

## 저장 방식을 비교한 이유

PASSTIVAL의 인증키 업로드는 엑셀에서 읽은 키를 모아 JPA의 `saveAll()`로 저장했다. 10만 행을 넣었을 때 INSERT도 10만 번 실행됐고 초기 측정에서 저장 구간이 전체 시간의 약 84%를 차지했다. 파서는 그대로 두고 저장 방식을 바꿔 보기로 했다.

인증키의 ID 생성 전략은 `IDENTITY`였다. 이 조건에서는 Hibernate의 배치 크기를 설정해도 INSERT가 묶이지 않았다. 별도로 1,000행을 넣어 확인했을 때도 INSERT 1,000회, JDBC 배치 실행 0회였다.

## 같은 파일로 네 가지 방식을 비교했다

JPA, JDBC 단건 저장, JDBC 배치, JDBC 배치에 SQL 재작성 옵션을 켠 방식을 비교했다. 파서와 테이블, 입력 파일은 같게 유지했다. 배치는 1,000행씩 실행하고 커밋은 네 방식 모두 파일이 끝날 때 한 번만 했다.

Apple M3, 메모리 16GiB, Java 17, 로컬 MySQL 8.0.46과 Connector/J 9.1.0에서 한 파일씩 처리했다. 아래는 10만 행을 방식별 5회 측정한 중앙값이다. 전체 시간에는 파싱과 커밋이 포함되며 HTTP 업로드는 제외했다.

| 저장 방식 | 전체 처리 시간 | MySQL INSERT 실행 |
| --- | ---: | ---: |
| JPA `saveAll()` | 4.05초 | 100,000회 |
| JDBC 단건 저장 | 3.23초 | 100,000회 |
| JDBC 배치 | 2.63초 | 100,000회 |
| JDBC 배치 + SQL 재작성 | 0.58초 | 100회 |

JDBC 배치를 호출하는 것만으로는 DB의 INSERT 횟수가 줄지 않았다. 같은 배치에 `rewriteBatchedStatements=true`를 적용하자 여러 행을 담은 INSERT로 재작성됐고 실행 횟수가 100회로 줄었다. 전체 처리 시간은 같은 실험에서 다시 측정한 JPA보다 약 85.7% 짧아졌다.

JDBC 단건 저장도 JPA보다 빨랐지만 엔티티 관리와 생성 ID 처리 등 여러 동작이 함께 달라졌다. 이 차이를 어느 한 기능의 비용으로 나누어 설명하지는 않았다.

## 저장 결과와 실패 후 데이터를 확인했다

각 실행이 끝난 뒤 DB의 키를 입력과 대조했다. 별도로 2,501행을 넣어 마지막 501행도 빠짐없이 저장되는지 확인했다.

실패한 업로드가 일부 데이터만 남겨서는 안 된다. 두 번째 배치에서 DB 오류를 일으켜 보니 첫 번째 배치까지 취소됐고 업로드 전에 있던 데이터는 그대로 남았다. 배치 실행은 나눠도 파일 전체를 하나의 트랜잭션으로 처리하는 조건을 유지했다.

## 적용 범위

인증키 업로드는 저장한 엔티티를 다시 수정하거나 생성 ID를 응답으로 돌려주지 않는다. 이 경로의 저장 작업을 JDBC로 분리하되 SQL과 컬럼 매핑을 직접 관리하는 비용을 받아들였다. 현재 구현에는 JDBC 배치와 재작성 옵션이 반영돼 있다.

다만 위 성능 수치는 실제 구현 반영 전에 실험용 저장 코드를 비교한 결과다. 반영 후에는 별도 MySQL 통합 테스트로 저장과 롤백을 확인했으며 같은 조건의 10만 행 성능을 다시 측정한 기록은 없다.

파일 전체를 메모리에 읽는 구조와 기존 셀 변환 방식은 그대로다. 실험에는 정상 처리되는 shared string 형식의 XLSX를 사용했다. 원격 DB나 동시 업로드의 성능은 이번 결과로 판단할 수 없다.

[원시 측정값과 실험 코드](https://github.com/goodjunseon/Passtival-server/tree/789228af5267ce8d32c84024b2eb2104029cfce1/backend/docs/benchmarks/excel-import-comparison)에서 상세 조건을 확인할 수 있다. 초기 병목 측정은 [기준선 자료](https://github.com/goodjunseon/Passtival-server/tree/789228af5267ce8d32c84024b2eb2104029cfce1/backend/docs/benchmarks/excel-import-baseline)에, 현재 구현의 검증은 [MySQL 통합 테스트](https://github.com/goodjunseon/Passtival-server/blob/789228af5267ce8d32c84024b2eb2104029cfce1/backend/src/test/java/com/passtival/backend/domain/authenticationkey/service/AuthenticationKeyImportMySqlTests.java)에 남아 있다.
