import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.hibernate.Version;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.passtival.backend.domain.authenticationkey.model.AuthenticationKey;
import com.passtival.backend.domain.authenticationkey.repository.AuthenticationKeyRepository;
import com.passtival.backend.domain.authenticationkey.service.AuthenticationKeyImportService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.LoggerFactory;

/**
 * Standalone baseline harness: loads the unchanged application service and entity.
 */
public class ExcelImportBaseline {

  private static final String URL = "jdbc:mysql://127.0.0.1:13317/passtival_excel_baseline"
      + "?sslMode=DISABLED&useServerPrepStmts=false&rewriteBatchedStatements=false";
  private static final boolean DIAGNOSTIC = Boolean.getBoolean("baseline.diagnostic");
  private static final int CONFIGURED_BATCH_SIZE = Integer.getInteger("baseline.hibernateBatchSize",
      0);
  private static final int[] SIZES =
      DIAGNOSTIC ? new int[]{1_000} : new int[]{1_000, 10_000, 50_000, 100_000};
  private static final int REPETITIONS = DIAGNOSTIC ? 1 : 5;
  private static final String INSERT_COUNT = "SELECT COALESCE(SUM(COUNT_STAR), 0) "
      + "FROM performance_schema.events_statements_summary_by_digest "
      + "WHERE SCHEMA_NAME = 'passtival_excel_baseline' "
      + "AND LOWER(DIGEST_TEXT) LIKE 'insert into%authentication_key%'";

  public static void main(String[] args) throws Exception {
    Locale.setDefault(Locale.ROOT);
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
    logging.getLogger("ROOT").setLevel(Level.WARN);
    logging.getLogger(AuthenticationKeyImportService.class).setLevel(Level.INFO);

    Map<Integer, MockMultipartFile> inputs = generateInputs(output);
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(URL);
    config.setUsername("root");
    config.setPassword(
        ""); // Disposable, loopback-only database, initialized specifically for this run.
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(1);
    config.setPoolName("excel-baseline");

    try (HikariDataSource pool = new HikariDataSource(config)) {
      execute(pool, "CREATE TABLE IF NOT EXISTS authentication_key ("
          + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
          + "authentication_key VARCHAR(5) NOT NULL, level INT NULL) ENGINE=InnoDB");
      Counts counts = new Counts();
      DataSource observed = observe(pool, counts);
      LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(observed);
      factory.setPersistenceUnitName("excel-baseline");
      factory.setPackagesToScan(AuthenticationKey.class.getPackageName());
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of(
          "hibernate.hbm2ddl.auto", "validate",
          "hibernate.jdbc.batch_size", Integer.toString(CONFIGURED_BATCH_SIZE),
          "hibernate.show_sql", "false",
          "hibernate.physical_naming_strategy",
          "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
      ));
      factory.afterPropertiesSet();
      EntityManagerFactory entityManagerFactory = factory.getObject();
      try {
        AuthenticationKeyRepository repository = new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
            .getRepository(AuthenticationKeyRepository.class);
        JpaTransactionManager manager = new JpaTransactionManager(entityManagerFactory);
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(new AuthenticationKeyImportService(repository));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        AuthenticationKeyImportService service = (AuthenticationKeyImportService) proxy.getProxy();

        writeEnvironment(output, pool, inputs);
        List<String> csv = new ArrayList<>();
        csv.add("phase,round,rows,file_bytes,parse_ms,save_flush_ms,total_commit_ms,commit_ms,"
            + "jdbc_insert_prepare_calls,jdbc_insert_execute_calls,jdbc_add_batch_calls,jdbc_execute_batch_calls,"
            + "mysql_insert_statements,stored_rows,distinct_keys,transaction_commits,gc_collections,gc_ms");
        for (int warmup = 1; warmup <= (DIAGNOSTIC ? 0 : 2); warmup++) {
          csv.add(measure("warmup", warmup, 10_000, inputs.get(10_000), service, pool, counts));
          Files.write(output.resolve("baseline.csv"), csv);
        }
        for (int round = 1; round <= REPETITIONS; round++) {
          // Rotate order to avoid always giving one size the earliest/coldest position.
          for (int offset = 0; offset < SIZES.length; offset++) {
            int rows = SIZES[(round - 1 + offset) % SIZES.length];
            csv.add(measure(DIAGNOSTIC ? "diagnostic" : "measured", round, rows, inputs.get(rows),
                service, pool, counts));
            Files.write(output.resolve("baseline.csv"), csv);
          }
        }
        Files.writeString(output.resolve("insert-sql.txt"), counts.sampleSql + "\n");
        System.out.println("BASELINE_COMPLETE " + output.toAbsolutePath());
      } finally {
        factory.destroy();
      }
    }
  }

  private static String measure(String phase, int round, int rows, MockMultipartFile file,
      AuthenticationKeyImportService service, DataSource pool, Counts counts) throws Exception {
    execute(pool, "TRUNCATE TABLE authentication_key");
    long serverBefore = scalar(pool, INSERT_COUNT);
    long gcCountBefore = gcCount();
    long gcMsBefore = gcMillis();
    counts.reset();
    counts.enabled = true;
    long start = System.nanoTime();
    AuthenticationKeyImportService.ImportSummary summary;
    try {
      summary = service.importXlsx(file, null);
    } finally {
      counts.enabled = false;
    }
    double totalMs = (System.nanoTime() - start) / 1_000_000.0;
    long gcCount = gcCount() - gcCountBefore;
    long gcMs = gcMillis() - gcMsBefore;
    long serverInserts = scalar(pool, INSERT_COUNT) - serverBefore;
    long stored = scalar(pool, "SELECT COUNT(*) FROM authentication_key");
    long distinct = scalar(pool,
        "SELECT COUNT(DISTINCT authentication_key) FROM authentication_key");
    long levels = scalar(pool, "SELECT COUNT(*) FROM authentication_key WHERE level IS NOT NULL");
    if (summary.readRows() != rows || summary.insertedCount() != rows || stored != rows
        || distinct != rows || levels != 0 || counts.commits != 1) {
      throw new AssertionError("Incorrect import: rows=" + rows + ", stored=" + stored
          + ", distinct=" + distinct + ", commits=" + counts.commits);
    }
    if (counts.insertExecutions != rows || counts.executeBatch != 0 || counts.addBatch != 0
        || serverInserts != rows) {
      throw new AssertionError("Unexpected baseline execution pattern: inserts="
          + counts.insertExecutions + ", serverInserts=" + serverInserts
          + ", executeBatch=" + counts.executeBatch);
    }
    String line = String.format(Locale.ROOT,
        "%s,%d,%d,%d,%d,%d,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
        phase, round, rows, file.getSize(), summary.parseElapsedMs(), summary.saveElapsedMs(),
        totalMs, counts.commitNanos / 1_000_000.0, counts.insertPrepares,
        counts.insertExecutions, counts.addBatch, counts.executeBatch, serverInserts,
        stored, distinct, counts.commits, gcCount, gcMs);
    System.out.println(line);
    return line;
  }

  private static Map<Integer, MockMultipartFile> generateInputs(Path output) throws Exception {
    Map<Integer, MockMultipartFile> inputs = new LinkedHashMap<>();
    for (int rows : SIZES) {
      Path path = output.resolve("keys-" + rows + ".xlsx");
      // Shared strings match the existing XSSFWorkbook-based API fixtures.
      // The unchanged importer clears inline-string values when calling setCellType(STRING).
      SXSSFWorkbook workbook = new SXSSFWorkbook(null, 100, true, true);
      try (workbook; OutputStream stream = Files.newOutputStream(path)) {
        Sheet sheet = workbook.createSheet("keys");
        for (int row = 0; row < rows; row++) {
          sheet.createRow(row).createCell(0).setCellValue(String.format(Locale.ROOT, "%05d", row));
        }
        workbook.write(stream);
      } finally {
        workbook.dispose();
      }
      inputs.put(rows, new MockMultipartFile("file", path.getFileName().toString(),
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          Files.readAllBytes(path)));
    }
    return inputs;
  }

  private static DataSource observe(DataSource pool, Counts counts) {
    return new AbstractDataSource() {
      @Override
      public Connection getConnection() throws java.sql.SQLException {
        return observeConnection(pool.getConnection(), counts);
      }

      @Override
      public Connection getConnection(String user, String password) throws java.sql.SQLException {
        return observeConnection(pool.getConnection(user, password), counts);
      }
    };
  }

  private static Connection observeConnection(Connection connection, Counts counts) {
    return (Connection) Proxy.newProxyInstance(ExcelImportBaseline.class.getClassLoader(),
        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
          if (counts.enabled && method.getName().equals("commit")) {
            long start = System.nanoTime();
            try {
              Object result = invoke(method, connection, args);
              counts.commits++;
              return result;
            } finally {
              counts.commitNanos += System.nanoTime() - start;
            }
          }
          Object result = invoke(method, connection, args);
          if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
              && sql.toLowerCase(Locale.ROOT).startsWith("insert into authentication_key")) {
            if (counts.enabled) {
              counts.insertPrepares++;
              counts.sampleSql = sql;
            }
            return Proxy.newProxyInstance(ExcelImportBaseline.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (statementProxy, statementMethod, statementArgs) -> {
                  if (counts.enabled) {
                    switch (statementMethod.getName()) {
                      case "execute", "executeUpdate", "executeLargeUpdate" ->
                          counts.insertExecutions++;
                      case "addBatch" -> counts.addBatch++;
                      case "executeBatch", "executeLargeBatch" -> counts.executeBatch++;
                    }
                  }
                  return invoke(statementMethod, result, statementArgs);
                });
          }
          return result;
        });
  }

  private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException exception) {
      throw exception.getCause();
    }
  }

  private static void execute(DataSource dataSource, String sql) throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long scalar(DataSource dataSource, String sql) throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static long gcCount() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(value -> value >= 0).sum();
  }

  private static long gcMillis() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(value -> value >= 0).sum();
  }

  private static void writeEnvironment(Path output, DataSource pool,
      Map<Integer, MockMultipartFile> inputs)
      throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("java=" + System.getProperty("java.runtime.version"));
    lines.add("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
        + " " + System.getProperty("os.arch"));
    lines.add("jvm_arguments=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
    lines.add("hibernate=" + Version.getVersionString());
    lines.add("spring_data_jpa=" + AuthenticationKeyRepository.class.getInterfaces()[0].getPackage()
        .getImplementationVersion());
    lines.add("apache_poi=" + org.apache.poi.Version.getVersion());
    lines.add("jdbc_url=" + URL);
    lines.add("hibernate.jdbc.batch_size=" + CONFIGURED_BATCH_SIZE);
    lines.add("hikari_maximum_pool_size=2; hikari_minimum_idle=1; concurrent_imports=1");
    lines.add("warmups=" + (DIAGNOSTIC ? "0 (execution-count probe only)" : "2 x 10000 rows")
        + "; measured_repetitions=" + REPETITIONS);
    lines.add("measured_sizes=" + Arrays.toString(SIZES));
    try (Connection connection = pool.getConnection()) {
      lines.add("mysql=" + connection.getMetaData().getDatabaseProductVersion());
      lines.add("driver=" + connection.getMetaData().getDriverVersion());
      try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
          "SHOW VARIABLES WHERE Variable_name IN ('innodb_buffer_pool_size', 'innodb_flush_log_at_trx_commit',"
              + " 'log_bin', 'performance_schema', 'sql_mode', 'transaction_isolation', 'character_set_server',"
              + " 'collation_server', 'max_allowed_packet')")) {
        while (result.next()) {
          lines.add(result.getString(1) + "=" + result.getString(2));
        }
      }
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("SHOW CREATE TABLE authentication_key")) {
        result.next();
        lines.add("schema=" + result.getString(2));
      }
    }
    for (Map.Entry<Integer, MockMultipartFile> input : inputs.entrySet()) {
      lines.add("file_bytes_" + input.getKey() + "=" + input.getValue().getSize());
    }
    Files.write(output.resolve("environment.txt"), lines);
  }

  private static class Counts {

    boolean enabled;
    long insertPrepares, insertExecutions, addBatch, executeBatch, commits, commitNanos;
    String sampleSql;

    void reset() {
      insertPrepares = insertExecutions = addBatch = executeBatch = commits = commitNanos = 0;
    }
  }
}
