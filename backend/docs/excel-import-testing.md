# 엑셀 업로드 테스트 준비

이 변경은 성능 개선 실험 전 사전 작업이다. 서버 시작 시 설정 경로의 파일을 자동으로 적재하던 방식을 제거하고, 테스트 API로 파일을 직접 전달한다.
`SEED_AUTH_KEYS_PATH` 설정은 더 이상 필요하지 않다.

## 요청

`POST /api/test/authentication-keys/import` (`multipart/form-data`)

| 필드             | 필수 | 설명                                          |
|----------------|----|---------------------------------------------|
| `file`         | 필수 | 읽을 `.xlsx` 파일. 현재 업로드 제한은 파일/요청 각각 10MB     |
| `rowsToImport` | 선택 | 1 이상의 정수. 생략하면 전체 행, 지정하면 첫 행부터 해당 행 수까지 처리 |

```bash
curl -X POST 'http://localhost:8080/api/test/authentication-keys/import' \
  -F 'file=@/absolute/path/authentication-keys.xlsx'
```

앞에서부터 최대 10,000행만 처리하려면 다음과 같이 요청한다.

```bash
curl -X POST 'http://localhost:8080/api/test/authentication-keys/import' \
  -F 'file=@/absolute/path/authentication-keys.xlsx' \
  -F 'rowsToImport=10000'
```

Swagger의 `Test-API`에서도 파일을 선택해 실행할 수 있다. 기존 JSON 요청은 multipart 요청으로 변경해야 한다.

## 읽기 및 저장 규칙

- 첫 번째 시트의 A열을 첫 행부터 읽는다. 헤더를 따로 건너뛰지 않으므로 헤더 없이 작성한다.
- 기존 문자열 변환 방식을 유지한다. 앞뒤 공백을 제거한 값의 길이가 5이면 저장하며, 다른 행은 건너뛴다. 앞자리 0이 필요한 키는 문자열 셀로 작성한다.
- `rowsToImport`는 저장할 키 개수가 아니라 읽을 행 범위다. 빈 행이나 유효하지 않은 행도 이 범위에 포함된다.
- 인증키의 `level`은 기존과 같이 `null`로 저장한다. 다른 시트와 B열 이후의 값은 읽지 않는다.
- 적재는 기존 데이터에 추가하는 방식이며, 재실행 시 중복 제거는 하지 않는다.
- 파일 전체를 `XSSFWorkbook`으로 읽고 엔티티를 리스트에 모아 `saveAll()`과 `flush()`로 저장하는 기존 방식을 유지한다. 스트리밍이나 DB 배치
  최적화는 이번 작업에 포함하지 않는다.

## 응답

기존 `BaseResponse` 형식을 사용한다. 성공 응답의 `result`에는 다음 값이 들어간다.

| 필드               | 의미                                          |
|------------------|---------------------------------------------|
| `requestedRows`  | 요청한 행 수. 생략한 경우 `null`                      |
| `readRows`       | 실제로 확인한 행 범위의 크기                            |
| `insertedCount`  | 이번 요청에서 저장한 인증키 수                           |
| `skippedRows`    | 빈 셀 또는 길이 조건 불일치로 건너뛴 행 수                   |
| `parseElapsedMs` | 파일 열기부터 엔티티 리스트 생성까지 걸린 시간                  |
| `saveElapsedMs`  | `saveAll()` 및 `flush()` 시간. 커밋 시간 제외        |
| `elapsedMs`      | 서비스 호출부터 트랜잭션 커밋까지 걸린 시간. HTTP 파일 업로드 시간 제외 |

기존 import 응답의 `beforeCount`, `afterCount`는 제거했다. 전체 테이블 건수 차이 대신 이번 요청에서 저장한 건수를 사용한다. 삭제 API의 응답은
기존과 같다.

빈 파일, 잘못된 파일, 시트가 없는 파일, 1 미만의 행 수는 실패 응답을 반환한다. 유효한 시트에서 저장 조건에 맞는 키가 없으면 저장 건수 0으로 성공한다. 프로젝트의 기존
응답 규칙에 따라 실패 여부와 오류 코드는 응답 본문의 `isSuccess`, `code`로 확인한다.

## 테스트 데이터 초기화

기존 `DELETE /api/test/authentication-keys/all` API로 인증키 테이블 전체를 비울 수 있다. 비교 실험에서 데이터를 초기화해야 하는 경우에만
사용한다.
