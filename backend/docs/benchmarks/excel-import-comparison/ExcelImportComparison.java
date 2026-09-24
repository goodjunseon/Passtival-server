import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.hibernate.Version;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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

/** Experiment only: reuse the real parser and transaction; substitute only repository writes. */
public class ExcelImportComparison {
  private static final String SCHEMA = "passtival_excel_comparison";
  private static final String URL = "jdbc:mysql://127.0.0.1:13318/" + SCHEMA
      + "?sslMode=DISABLED&useServerPrepStmts=false&rewriteBatchedStatements=";
  private static final String INSERT =
      "insert into authentication_key (authentication_key,level) values (?,?)";
  private static final int BATCH_SIZE = 1_000;
  private static final int[] SIZES = {1_000, 10_000, 50_000, 100_000};
  private static final int REPETITIONS = 5;
  private static final String INSERT_COUNT = "SELECT COALESCE(SUM(COUNT_STAR), 0) "
      + "FROM performance_schema.events_statements_summary_by_digest "
      + "WHERE SCHEMA_NAME = '" + SCHEMA + "' "
      + "AND LOWER(DIGEST_TEXT) LIKE 'insert into%authentication_key%'";

  enum Variant { JPA, JDBC_SINGLE, JDBC_BATCH, JDBC_BATCH_REWRITE }

  public static void main(String[] args) throws Exception {
    Locale.setDefault(Locale.ROOT);
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
    logging.getLogger("ROOT").setLevel(Level.WARN);
    logging.getLogger(AuthenticationKeyImportService.class).setLevel(Level.INFO);
    Map<Integer, MockMultipartFile> inputs = new LinkedHashMap<>();
    for (int size : SIZES) inputs.put(size, generateInput(output, size));
    MockMultipartFile failureInput = generateInput(output, 2_501);

    try (Context regular = new Context(false); Context rewritten = new Context(true)) {
      writeEnvironment(output, regular.pool);
      Map<Variant, AuthenticationKeyImportService> services = new LinkedHashMap<>();
      for (Variant variant : Variant.values()) {
        services.put(variant, context(variant, regular, rewritten).service(variant));
      }
      List<String> csv = new ArrayList<>();
      csv.add("phase,round,variant,rows,file_bytes,parse_ms,save_flush_ms,total_commit_ms,commit_ms,"
          + "jdbc_insert_prepare_calls,jdbc_insert_execute_calls,jdbc_add_batch_calls,jdbc_execute_batch_calls,"
          + "mysql_insert_statements,stored_rows,distinct_keys,transaction_commits,transaction_rollbacks,"
          + "gc_collections,gc_ms");
      for (int warmup = 1; warmup <= 2; warmup++) {
        for (Variant variant : Variant.values()) {
          csv.add(measure("warmup", warmup, variant, 10_000, inputs.get(10_000),
              services.get(variant), context(variant, regular, rewritten)));
          Files.write(output.resolve("comparison.csv"), csv);
        }
      }
      for (int round = 1; round <= REPETITIONS; round++) {
        for (int sizeOffset = 0; sizeOffset < SIZES.length; sizeOffset++) {
          int rows = SIZES[(round - 1 + sizeOffset) % SIZES.length];
          for (int variantOffset = 0; variantOffset < Variant.values().length; variantOffset++) {
            Variant variant = Variant.values()[(round - 1 + sizeOffset + variantOffset) % 4];
            csv.add(measure("measured", round, variant, rows, inputs.get(rows),
                services.get(variant), context(variant, regular, rewritten)));
            Files.write(output.resolve("comparison.csv"), csv);
          }
        }
      }

      // Verify a partial final batch as well as a failure after at least one completed batch.
      List<String> correctness = new ArrayList<>();
      correctness.add("variant,input_rows,stored_rows,execute_batch_calls,commits,exact_values");
      List<String> rollback = new ArrayList<>();
      rollback.add("variant,input_rows,rejected_key,successful_single_inserts,successful_batches,"
          + "execute_batch_attempts,commits,rollbacks,remaining_rows,mysql_error_code,sentinel_preserved");
      for (Variant variant : Variant.values()) {
        Context current = context(variant, regular, rewritten);
        measure("correctness", 0, variant, 2_501, failureInput, services.get(variant), current);
        correctness.add(variant + ",2501,2501," + current.counts.executeBatch + ",1,true");
        rollback.add(verifyRollback(variant, failureInput, services.get(variant), current));
        Files.write(output.resolve("correctness.csv"), correctness);
        Files.write(output.resolve("rollback.csv"), rollback);
      }
      Files.writeString(output.resolve("insert-sql.txt"), INSERT + "\n");
      System.out.println("COMPARISON_COMPLETE " + output.toAbsolutePath());
    }
  }

  private static Context context(Variant variant, Context regular, Context rewritten) {
    return variant == Variant.JDBC_BATCH_REWRITE ? rewritten : regular;
  }

  private static final class Context implements AutoCloseable {
    final HikariDataSource pool;
    final Counts counts = new Counts();
    final DataSource observed;
    final LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
    final AuthenticationKeyRepository repository;
    final JpaTransactionManager manager;

    Context(boolean rewrite) throws Exception {
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(URL + rewrite);
      config.setUsername("root");
      config.setPassword(""); // Fresh disposable DB, loopback only; see run.sh.
      config.setMaximumPoolSize(2);
      config.setMinimumIdle(1);
      config.setPoolName("comparison-rewrite-" + rewrite);
      pool = new HikariDataSource(config);
      try {
        execute(pool, "CREATE TABLE IF NOT EXISTS authentication_key ("
            + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
            + "authentication_key VARCHAR(5) NOT NULL, level INT NULL) ENGINE=InnoDB");
        observed = observe(pool, counts);
        factory.setDataSource(observed);
        factory.setPersistenceUnitName("comparison-rewrite-" + rewrite);
        factory.setPackagesToScan(AuthenticationKey.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
            "hibernate.hbm2ddl.auto", "validate", "hibernate.jdbc.batch_size", "0",
            "hibernate.show_sql", "false", "hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        factory.afterPropertiesSet();
        EntityManagerFactory emf = factory.getObject();
        repository = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
            .getRepository(AuthenticationKeyRepository.class);
        manager = new JpaTransactionManager(emf);
        manager.setDataSource(observed); // JdbcTemplate joins the same transaction/connection.
      } catch (Exception | Error failure) {
        factory.destroy();
        pool.close();
        throw failure;
      }
    }

    AuthenticationKeyImportService service(Variant variant) {
      AuthenticationKeyRepository writer = repository;
      if (variant != Variant.JPA) {
        JdbcTemplate jdbc = new JdbcTemplate(observed);
        // This adapter is confined to the experiment. Returned entities need no generated IDs here.
        writer = (AuthenticationKeyRepository) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{AuthenticationKeyRepository.class},
            (proxy, method, args) -> {
              if (method.getName().equals("saveAll")) {
                @SuppressWarnings("unchecked")
                List<AuthenticationKey> entities = (List<AuthenticationKey>) args[0];
                if (variant == Variant.JDBC_SINGLE) {
                  for (AuthenticationKey entity : entities) {
                    jdbc.update(INSERT, statement -> bind(statement, entity));
                  }
                } else {
                  jdbc.batchUpdate(INSERT, entities, BATCH_SIZE, ExcelImportComparison::bind);
                }
                return entities;
              }
              if (method.getName().equals("flush")) return null; // JDBC already executed all writes.
              return invoke(method, repository, args);
            });
      }
      TransactionInterceptor advice = new TransactionInterceptor();
      advice.setTransactionManager(manager);
      advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
      ProxyFactory proxy = new ProxyFactory(new AuthenticationKeyImportService(writer));
      proxy.setProxyTargetClass(true);
      proxy.addAdvice(advice);
      return (AuthenticationKeyImportService) proxy.getProxy();
    }

    @Override public void close() {
      try { factory.destroy(); } finally { pool.close(); }
    }
  }

  private static void bind(PreparedStatement statement, AuthenticationKey entity) throws SQLException {
    statement.setString(1, entity.getAuthenticationKey());
    if (entity.getLevel() == null) statement.setNull(2, Types.INTEGER);
    else statement.setInt(2, entity.getLevel());
  }

  private static String measure(String phase, int round, Variant variant, int rows,
      MockMultipartFile file, AuthenticationKeyImportService service, Context context) throws Exception {
    execute(context.pool, "TRUNCATE TABLE authentication_key");
    long serverBefore = scalar(context.pool, INSERT_COUNT);
    long gcCountBefore = gcCount();
    long gcMsBefore = gcMillis();
    Counts counts = context.counts;
    counts.reset();
    counts.enabled = true;
    long start = System.nanoTime();
    AuthenticationKeyImportService.ImportSummary summary;
    try { summary = service.importXlsx(file, null); }
    finally { counts.enabled = false; }
    double totalMs = (System.nanoTime() - start) / 1_000_000.0;
    long gcCount = gcCount() - gcCountBefore;
    long gcMs = gcMillis() - gcMsBefore;
    long serverInserts = scalar(context.pool, INSERT_COUNT) - serverBefore;
    assertExactValues(context.pool, rows);
    require(summary.readRows() == rows && summary.insertedCount() == rows
        && counts.commits == 1 && counts.rollbacks == 0, "Incorrect import or transaction: " + variant);
    int batches = (rows + BATCH_SIZE - 1) / BATCH_SIZE;
    boolean batched = variant == Variant.JDBC_BATCH || variant == Variant.JDBC_BATCH_REWRITE;
    require(counts.insertExecutions == (batched ? 0 : rows)
        && counts.addBatch == (batched ? rows : 0)
        && counts.executeBatch == (batched ? batches : 0)
        && counts.successfulBatches == (batched ? batches : 0)
        && counts.insertPrepares == (batched ? 1 : rows), "Unexpected JDBC pattern: " + variant);
    require(serverInserts == (variant == Variant.JDBC_BATCH_REWRITE ? batches : rows),
        "Unexpected MySQL INSERT count: " + variant + " " + serverInserts);
    String line = String.format(Locale.ROOT,
        "%s,%d,%s,%d,%d,%d,%d,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
        phase, round, variant, rows, file.getSize(), summary.parseElapsedMs(), summary.saveElapsedMs(),
        totalMs, counts.commitNanos / 1_000_000.0, counts.insertPrepares, counts.insertExecutions,
        counts.addBatch, counts.executeBatch, serverInserts, rows, rows, counts.commits,
        counts.rollbacks, gcCount, gcMs);
    System.out.println(line);
    return line;
  }

  private static String verifyRollback(Variant variant, MockMultipartFile file,
      AuthenticationKeyImportService service, Context context) throws Exception {
    execute(context.pool, "TRUNCATE TABLE authentication_key");
    execute(context.pool, "INSERT INTO authentication_key (authentication_key,level) VALUES ('ZZZZZ',7)");
    execute(context.pool, "ALTER TABLE authentication_key ADD CONSTRAINT benchmark_reject_key "
        + "CHECK (authentication_key <> '01500')");
    Counts counts = context.counts;
    try {
      counts.reset();
      counts.enabled = true;
      Exception failure = null;
      try { service.importXlsx(file, null); }
      catch (Exception exception) { failure = exception; }
      finally { counts.enabled = false; }
      require(failure != null, "Expected constraint failure: " + variant);
      boolean expectedError = false;
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof SQLException sql && sql.getErrorCode() == 3819
            && sql.getMessage().contains("benchmark_reject_key")) expectedError = true;
      }
      require(expectedError, "Unexpected failure: " + failure);
      require(counts.commits == 0 && counts.rollbacks == 1, "Rollback missing: " + variant);
      boolean batched = variant == Variant.JDBC_BATCH || variant == Variant.JDBC_BATCH_REWRITE;
      require(batched ? counts.successfulBatches >= 1 : counts.successfulInserts >= 1_000,
          "Failure occurred before earlier writes completed: " + variant);
      require(scalar(context.pool, "SELECT COUNT(*) FROM authentication_key") == 1
          && scalar(context.pool, "SELECT COUNT(*) FROM authentication_key "
              + "WHERE id=1 AND authentication_key='ZZZZZ' AND level=7") == 1,
          "Partial data survived or preexisting data changed: " + variant);
      String line = variant + ",2501,01500," + counts.successfulInserts + ","
          + counts.successfulBatches + "," + counts.executeBatch + "," + counts.commits + ","
          + counts.rollbacks + ",1,3819,true";
      System.out.println("ROLLBACK_PASS " + line);
      return line;
    } finally {
      execute(context.pool, "ALTER TABLE authentication_key DROP CHECK benchmark_reject_key");
    }
  }

  private static void assertExactValues(DataSource pool, int rows) throws Exception {
    try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(
            "SELECT authentication_key,level,id FROM authentication_key ORDER BY authentication_key")) {
      for (int index = 0; index < rows; index++) {
        require(result.next(), "Missing row " + index);
        require(String.format(Locale.ROOT, "%05d", index).equals(result.getString(1))
            && result.getObject(2) == null && result.getLong(3) > 0, "Incorrect row " + index);
      }
      require(!result.next(), "Unexpected extra rows");
    }
  }

  private static MockMultipartFile generateInput(Path output, int rows) throws Exception {
    Path path = output.resolve("keys-" + rows + ".xlsx");
    SXSSFWorkbook workbook = new SXSSFWorkbook(null, 100, true, true);
    try (workbook; OutputStream stream = Files.newOutputStream(path)) {
      Sheet sheet = workbook.createSheet("keys");
      for (int row = 0; row < rows; row++) {
        sheet.createRow(row).createCell(0).setCellValue(String.format(Locale.ROOT, "%05d", row));
      }
      workbook.write(stream);
    } finally { workbook.dispose(); }
    byte[] bytes = Files.readAllBytes(path);
    Files.writeString(output.resolve("input-sha256.txt"),
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            + "  " + path.getFileName() + "\n",
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    return new MockMultipartFile("file", path.getFileName().toString(),
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
  }

  private static DataSource observe(DataSource pool, Counts counts) {
    return new AbstractDataSource() {
      @Override public Connection getConnection() throws SQLException {
        return observeConnection(pool.getConnection(), counts);
      }
      @Override public Connection getConnection(String user, String password) throws SQLException {
        return observeConnection(pool.getConnection(user, password), counts);
      }
    };
  }

  private static Connection observeConnection(Connection connection, Counts counts) {
    return (Connection) Proxy.newProxyInstance(ExcelImportComparison.class.getClassLoader(),
        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
          if (counts.enabled && method.getName().equals("commit")) {
            long start = System.nanoTime();
            try {
              Object result = invoke(method, connection, args);
              counts.commits++;
              return result;
            } finally { counts.commitNanos += System.nanoTime() - start; }
          }
          if (counts.enabled && method.getName().equals("rollback")) counts.rollbacks++;
          Object result = invoke(method, connection, args);
          if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
              && sql.toLowerCase(Locale.ROOT).startsWith("insert into authentication_key")) {
            if (counts.enabled) counts.insertPrepares++;
            return Proxy.newProxyInstance(ExcelImportComparison.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                  String name = statementMethod.getName();
                  if (counts.enabled) {
                    switch (name) {
                      case "execute", "executeUpdate", "executeLargeUpdate" -> counts.insertExecutions++;
                      case "addBatch" -> counts.addBatch++;
                      case "executeBatch", "executeLargeBatch" -> counts.executeBatch++;
                    }
                  }
                  Object returned = invoke(statementMethod, result, statementArgs);
                  if (counts.enabled) {
                    switch (name) {
                      case "execute", "executeUpdate", "executeLargeUpdate" -> counts.successfulInserts++;
                      case "executeBatch", "executeLargeBatch" -> counts.successfulBatches++;
                    }
                  }
                  return returned;
                });
          }
          return result;
        });
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try { return method.invoke(target, args); }
    catch (InvocationTargetException exception) { throw exception.getCause(); }
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

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static long gcCount() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(java.lang.management.GarbageCollectorMXBean::getCollectionCount)
        .filter(value -> value >= 0).sum();
  }

  private static long gcMillis() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(java.lang.management.GarbageCollectorMXBean::getCollectionTime)
        .filter(value -> value >= 0).sum();
  }

  private static void writeEnvironment(Path output, DataSource pool) throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("java=" + System.getProperty("java.runtime.version"));
    lines.add("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
        + " " + System.getProperty("os.arch"));
    lines.add("jvm_arguments=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
    lines.add("hibernate=" + Version.getVersionString());
    lines.add("spring=" + org.springframework.core.SpringVersion.getVersion());
    lines.add("spring_data_jpa=" + AuthenticationKeyRepository.class.getInterfaces()[0].getPackage()
        .getImplementationVersion());
    lines.add("apache_poi=" + org.apache.poi.Version.getVersion());
    lines.add("jdbc_url_A_B_C=" + URL + "false");
    lines.add("jdbc_url_D=" + URL + "true");
    lines.add("hibernate.jdbc.batch_size=0; jdbc_batch_size=" + BATCH_SIZE);
    lines.add("pools=2 (rewrite=false/true); each_pool_maximum=2; each_pool_minimum_idle=1");
    lines.add("concurrent_imports=1; transaction_manager=JpaTransactionManager (all variants)");
    lines.add("warmups=2 x 10000 per variant; repetitions=" + REPETITIONS);
    lines.add("sizes=1000,10000,50000,100000; rotate_size_and_variant_order=true");
    lines.add("correctness=exact ordered keys, null levels, positive DB IDs, commit=1; outside timer");
    lines.add("rollback=2501 rows; reject 01500 via temporary CHECK; preserve ZZZZZ/7/id=1");
    try (Connection connection = pool.getConnection()) {
      lines.add("mysql=" + connection.getMetaData().getDatabaseProductVersion());
      lines.add("driver=" + connection.getMetaData().getDriverVersion());
      try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
          "SHOW VARIABLES WHERE Variable_name IN ('innodb_buffer_pool_size', 'innodb_flush_log_at_trx_commit',"
              + " 'log_bin', 'performance_schema', 'sql_mode', 'transaction_isolation', 'max_allowed_packet',"
              + " 'character_set_server', 'collation_server')")) {
        while (result.next()) lines.add(result.getString(1) + "=" + result.getString(2));
      }
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("SHOW CREATE TABLE authentication_key")) {
        result.next();
        lines.add("schema=" + result.getString(2));
      }
    }
    Files.write(output.resolve("environment.txt"), lines);
  }

  private static class Counts {
    boolean enabled;
    long insertPrepares, insertExecutions, addBatch, executeBatch, commits, rollbacks, commitNanos;
    long successfulInserts, successfulBatches;
    void reset() {
      insertPrepares = insertExecutions = addBatch = executeBatch = commits = rollbacks = commitNanos = 0;
      successfulInserts = successfulBatches = 0;
    }
  }
}
