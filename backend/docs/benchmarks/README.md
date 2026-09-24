# 리팩터링 전 실험의 재현

이 디렉터리의 기준선·저장 방식 비교·배치 크기 실험은 모두 애플리케이션 커밋
`2799a43dd1ff3bf0803b9c2095cec47a87079277`을 대상으로 수행했다. 실험용 어댑터는 당시 서비스의 JPA 저장소 생성자를 사용한다.
현재 서비스는 실제 JDBC 저장 컴포넌트를 받으므로, 역사적 실험 코드는 아래처럼 **당시 애플리케이션 소스가 있는 별도 checkout에서 실행**해야 한다.
실행했던 Java 코드와 스크립트, 원시 측정값은 그 상태로 보존했다.

저장소 루트에서 다음을 실행한다.

```bash
benchmark_checkout=$(mktemp -d /private/tmp/passtival-benchmark-source.XXXXXX)
git worktree add --detach "$benchmark_checkout" 2799a43dd1ff3bf0803b9c2095cec47a87079277
cp -R backend/docs/benchmarks "$benchmark_checkout/backend/docs/"
cd "$benchmark_checkout"

# 저장 방식 비교
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  bash backend/docs/benchmarks/excel-import-comparison/run.sh

# 배치 크기 비교
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  bash backend/docs/benchmarks/excel-batch-size/run.sh
```

JPA 기준선의 수동 실행 명령도 위 checkout의 `backend` 디렉터리에서 실행한다.
집계 스크립트는 애플리케이션 소스와 무관하므로 현재 checkout의 보존된 CSV에 직접 실행해도 된다.
`harness-sha256.txt`에 남은 절대 경로는 최초 실행 당시 위치이며, 다른 위치에서 파일을 확인할 때는 파일명별 SHA-256 값을 비교한다.

현재 실제 JDBC 구현의 검증은 `backend/scripts/test-excel-jdbc.sh`와 `AuthenticationKeyImportMySqlTests`를 사용한다.
역사적 하네스를 현재 서비스에 억지로 맞추거나, 과거 측정값을 현재 코드의 재측정값으로 해석하지 않는다.
