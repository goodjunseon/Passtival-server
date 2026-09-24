package com.passtival.backend.domain.authenticationkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import com.passtival.backend.domain.authenticationkey.model.AuthenticationKey;
import com.passtival.backend.domain.authenticationkey.repository.AuthenticationKeyJdbcRepository;
import com.zaxxer.hikari.HikariDataSource;

/** Run with scripts/test-excel-jdbc.sh against its disposable, loopback-only MySQL. */
@EnabledIfEnvironmentVariable(
    named = "EXCEL_TEST_MYSQL_URL",
    matches = "jdbc:mysql://127\\.0\\.0\\.1:13320/passtival_excel_refactor_test")
@SpringBootTest(
    classes = AuthenticationKeyImportMySqlTests.TestConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.datasource.url=${EXCEL_TEST_MYSQL_URL}",
        "spring.datasource.username=root",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.docker.compose.enabled=false"
    })
class AuthenticationKeyImportMySqlTests {

  @Autowired
  private AuthenticationKeyImportService service;

  @Autowired
  private AuthenticationKeyJdbcRepository repository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private HikariDataSource dataSource;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @BeforeEach
  void resetDedicatedTestTable() {
    assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
    // This is bound from application.yml, not overridden by this test.
    assertThat(dataSource.getDataSourceProperties().getProperty("rewriteBatchedStatements"))
        .isEqualTo("true");
    assertThat(jdbcTemplate.queryForObject("SELECT @@performance_schema", Integer.class))
        .isEqualTo(1);
    jdbcTemplate.execute("TRUNCATE TABLE authentication_key");
  }

  @Test
  void importsEveryValueIncludingThePartialLastBatchAsThreeMysqlStatements() throws Exception {
    List<String> keys = keys(2_501);
    long insertsBefore = mysqlInsertCount();

    AuthenticationKeyImportService.ImportSummary result = service.importXlsx(xlsx(keys), null);

    assertThat(result.readRows()).isEqualTo(2_501);
    assertThat(result.insertedCount()).isEqualTo(2_501);
    assertThat(storedKeys()).containsExactlyElementsOf(keys);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM authentication_key WHERE level IS NOT NULL", Long.class))
        .isZero();
    assertThat(mysqlInsertCount() - insertsBefore).isEqualTo(3);
  }

  @Test
  void doesNotExecuteInsertsWhenTheFileHasNoValidKeys() throws Exception {
    long insertsBefore = mysqlInsertCount();

    AuthenticationKeyImportService.ImportSummary result =
        service.importXlsx(xlsx(List.of("bad", "1234")), null);

    assertThat(result.readRows()).isEqualTo(2);
    assertThat(result.insertedCount()).isZero();
    assertThat(storedKeys()).isEmpty();
    assertThat(mysqlInsertCount() - insertsBefore).isZero();
  }

  @Test
  void writerPreservesBothExplicitAndNullLevels() {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> repository.insertAll(
        List.of(new AuthenticationKey("00001", 3), new AuthenticationKey("00002", null),
            new AuthenticationKey("00003", 7))));

    assertThat(jdbcTemplate.query("SELECT level FROM authentication_key ORDER BY id",
        (resultSet, row) -> resultSet.getObject("level", Integer.class)))
        .containsExactly(3, null, 7);
  }

  @Test
  void failureInSecondBatchRollsBackFirstBatchAndPreservesPreviouslyCommittedData()
      throws Exception {
    jdbcTemplate.update(
        "INSERT INTO authentication_key (authentication_key, level) VALUES (?, ?)", "EXIST", 7);
    jdbcTemplate.execute("ALTER TABLE authentication_key ADD CONSTRAINT reject_test_failure "
        + "CHECK (authentication_key <> 'FAIL!')");
    try {
      List<String> keys = new java.util.ArrayList<>(keys(2_501));
      keys.set(1_250, "FAIL!"); // Accepted by the parser, rejected by MySQL in batch two.
      MockMultipartFile file = xlsx(keys);
      long insertsBefore = mysqlInsertCount();

      assertThatThrownBy(() -> service.importXlsx(file, null))
          .isInstanceOf(DataAccessException.class);

      assertThat(mysqlInsertCount() - insertsBefore).isEqualTo(2);
      assertThat(storedKeys()).containsExactly("EXIST");
      assertThat(jdbcTemplate.queryForObject(
          "SELECT level FROM authentication_key WHERE authentication_key = 'EXIST'", Integer.class))
          .isEqualTo(7);
    } finally {
      jdbcTemplate.execute("ALTER TABLE authentication_key DROP CHECK reject_test_failure");
    }
  }

  private long mysqlInsertCount() {
    return jdbcTemplate.queryForObject("""
        SELECT COALESCE(SUM(COUNT_STAR), 0)
        FROM performance_schema.events_statements_summary_by_digest
        WHERE SCHEMA_NAME = DATABASE()
          AND DIGEST_TEXT LIKE 'INSERT INTO %authentication_key%'
        """, Long.class);
  }

  private List<String> storedKeys() {
    return jdbcTemplate.queryForList(
        "SELECT authentication_key FROM authentication_key ORDER BY id", String.class);
  }

  private List<String> keys(int count) {
    return IntStream.range(0, count).mapToObj(index -> String.format("%05d", index)).toList();
  }

  private MockMultipartFile xlsx(List<String> keys) throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet();
      for (int index = 0; index < keys.size(); index++) {
        sheet.createRow(index).createCell(0).setCellValue(keys.get(index));
      }
      workbook.write(output);
      return new MockMultipartFile("file", "keys.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  @EntityScan(basePackageClasses = AuthenticationKey.class)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
      HibernateJpaAutoConfiguration.class})
  @Import({AuthenticationKeyImportService.class, AuthenticationKeyJdbcRepository.class})
  static class TestConfiguration {
  }
}
