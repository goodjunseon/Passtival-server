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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.hibernate.Version;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
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

/** Local experiment only. Uses the unchanged application parser and transaction annotation. */
public class ExcelBatchSizeBenchmark {
  private static final String SCHEMA = "passtival_excel_batch_size";
  private static final String URL = "jdbc:mysql://127.0.0.1:13319/" + SCHEMA
      + "?sslMode=DISABLED&useServerPrepStmts=false&rewriteBatchedStatements=true";
  private static final String INSERT =
      "insert into authentication_key (authentication_key,level) values (?,?)";
  private static final int[] SIZES = {10_000, 100_000};
  private static final int[] BATCHES = Arrays.stream(
      System.getProperty("batch.sizes", "100,500,1000,2000,5000").split(","))
      .mapToInt(Integer::parseInt).toArray();
  private static final int ROUNDS = 10;
  private static final String INSERT_COUNT = "SELECT COALESCE(SUM(COUNT_STAR), 0) "
      + "FROM performance_schema.events_statements_summary_by_digest "
      + "WHERE SCHEMA_NAME = '" + SCHEMA + "' "
      + "AND LOWER(DIGEST_TEXT) LIKE 'insert into%authentication_key%'";
  private static final com.sun.management.ThreadMXBean ALLOCATION =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

  public static void main(String[] args) throws Exception {
    Locale.setDefault(Locale.ROOT);
    Path output = Path.of(args[0]);
    int fork = Integer.parseInt(args[1]);
    require(!Files.exists(output), "Use a new output directory");
    require(Arrays.stream(BATCHES).allMatch(value -> value > 0 && value <= 40_000)
        && Arrays.stream(BATCHES).distinct().count() == BATCHES.length, "Invalid batch sizes");
    require(ALLOCATION.isThreadAllocatedMemorySupported(), "Allocation counter unavailable");
    ALLOCATION.setThreadAllocatedMemoryEnabled(true);
    Files.createDirectories(output);
    LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
    logging.getLogger("ROOT").setLevel(Level.WARN);
    logging.getLogger(AuthenticationKeyImportService.class).setLevel(Level.INFO);
    Map<Integer, MockMultipartFile> inputs = new LinkedHashMap<>();
    for (int rows : SIZES) inputs.put(rows, input(output, rows));
    for (int batch : BATCHES) {
      int rows = 2 * batch + 1;
      if (!inputs.containsKey(rows)) inputs.put(rows, input(output, rows));
    }

    try (Context context = new Context(); HeapSampler heap = new HeapSampler()) {
      writeEnvironment(output, fork, context.pool);
      Map<Integer, AuthenticationKeyImportService> services = new LinkedHashMap<>();
      for (int batch : BATCHES) services.put(batch, context.service(batch));
      List<String> csv = new ArrayList<>();
      csv.add("phase,fork,round,rows,batch_size,file_bytes,jdbc_write_ms,commit_ms,write_commit_ms,"
          + "parse_ms,service_save_ms,total_ms,insert_prepares,single_executes,add_batch,execute_batch,"
          + "mysql_inserts,stored_rows,exact_values,commits,rollbacks,gc_count,gc_ms,"
          + "thread_allocated_bytes,write_allocated_bytes,heap_before_bytes,heap_sampled_peak_bytes");
      for (int round = 1; round <= 2; round++) {
        for (int offset = 0; offset < BATCHES.length; offset++) {
          int batch = BATCHES[(offset + fork + round - 2) % BATCHES.length];
          csv.add(measure("warmup", fork, round, 100_000, batch, inputs.get(100_000),
              services.get(batch), context, heap));
          Files.write(output.resolve("measurements.csv"), csv);
        }
      }
      for (int round = 1; round <= ROUNDS; round++) {
        for (int sizeOffset = 0; sizeOffset < SIZES.length; sizeOffset++) {
          int rows = SIZES[(round + fork + sizeOffset) % SIZES.length];
          for (int offset = 0; offset < BATCHES.length; offset++) {
            // Rotate and reverse order to distribute warm-cache and GC effects across candidates.
            int direction = round % 2 == 0 ? 1 : -1;
            int index = Math.floorMod(round + fork + sizeOffset + direction * offset, BATCHES.length);
            int batch = BATCHES[index];
            csv.add(measure("measured", fork, round, rows, batch, inputs.get(rows),
                services.get(batch), context, heap));
            Files.write(output.resolve("measurements.csv"), csv);
          }
        }
      }
      List<String> correctness = new ArrayList<>();
      correctness.add("fork,batch_size,input_rows,stored_rows,execute_batch,commits,exact_values");
      List<String> rollback = new ArrayList<>();
      rollback.add("fork,batch_size,input_rows,rejected_key,successful_batches,execute_batch_attempts,"
          + "commits,rollbacks,remaining_rows,mysql_error_code,sentinel_preserved");
      for (int batch : BATCHES) {
        int rows = 2 * batch + 1;
        measure("correctness", fork, 0, rows, batch, inputs.get(rows), services.get(batch), context, heap);
        correctness.add(fork + "," + batch + "," + rows + "," + rows + ",3,1,true");
        rollback.add(verifyRollback(fork, batch, inputs.get(rows), services.get(batch), context));
        Files.write(output.resolve("correctness.csv"), correctness);
        Files.write(output.resolve("rollback.csv"), rollback);
      }
      Files.writeString(output.resolve("insert-sql.txt"), INSERT + "\n");
      System.out.println("BATCH_SIZE_COMPLETE fork=" + fork + " " + output.toAbsolutePath());
    }
  }

  private static final class Context implements AutoCloseable {
    final HikariDataSource pool;
    final Counts counts = new Counts();
    final LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
    final DataSource observed;
    final JpaTransactionManager manager;

    Context() throws Exception {
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(URL);
      config.setUsername("root");
      config.setPassword(""); // Dedicated, fresh, loopback-only MySQL started by run.sh.
      config.setMaximumPoolSize(2);
      config.setMinimumIdle(1);
      config.setPoolName("batch-size");
      pool = new HikariDataSource(config);
      try {
        execute(pool, "CREATE TABLE IF NOT EXISTS authentication_key (id BIGINT NOT NULL "
            + "AUTO_INCREMENT PRIMARY KEY, authentication_key VARCHAR(5) NOT NULL, level INT NULL) ENGINE=InnoDB");
        observed = new AbstractDataSource() {
          @Override public Connection getConnection() throws SQLException {
            return observe(pool.getConnection(), counts);
          }
          @Override public Connection getConnection(String user, String password) throws SQLException {
            return observe(pool.getConnection(user, password), counts);
          }
        };
        factory.setDataSource(observed);
        factory.setPersistenceUnitName("batch-size");
        factory.setPackagesToScan(AuthenticationKey.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
            "hibernate.jdbc.batch_size", "0", "hibernate.show_sql", "false",
            "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        factory.afterPropertiesSet();
        manager = new JpaTransactionManager(factory.getObject());
        manager.setDataSource(observed);
      } catch (Exception | Error failure) {
        factory.destroy();
        pool.close();
        throw failure;
      }
    }

    AuthenticationKeyImportService service(int batch) {
      JdbcTemplate jdbc = new JdbcTemplate(observed);
      AuthenticationKeyRepository writer = (AuthenticationKeyRepository) Proxy.newProxyInstance(
          getClass().getClassLoader(), new Class<?>[]{AuthenticationKeyRepository.class},
          (proxy, method, args) -> {
            if (method.getName().equals("saveAll")) {
              @SuppressWarnings("unchecked")
              List<AuthenticationKey> entities = (List<AuthenticationKey>) args[0];
              long allocationStart = allocated();
              long start = System.nanoTime();
              try { jdbc.batchUpdate(INSERT, entities, batch, ExcelBatchSizeBenchmark::bind); }
              finally {
                counts.writeNanos = System.nanoTime() - start;
                counts.writeAllocated = allocated() - allocationStart;
              }
              return entities; // Generated IDs are unused by the real import service.
            }
            if (method.getName().equals("flush")) return null;
            if (method.getName().equals("toString")) return "ExperimentBatchWriter[" + batch + "]";
            throw new UnsupportedOperationException("Experiment supports saveAll and flush only: " + method);
          });
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

  private static String measure(String phase, int fork, int round, int rows, int batch,
      MockMultipartFile file, AuthenticationKeyImportService service, Context context, HeapSampler heap)
      throws Exception {
    execute(context.pool, "TRUNCATE TABLE authentication_key");
    long serverBefore = scalar(context.pool, INSERT_COUNT);
    long gcCountBefore = gcCount(), gcMsBefore = gcMillis();
    Counts counts = context.counts;
    counts.reset();
    long heapBefore = heap.start();
    long allocatedBefore = allocated();
    counts.enabled = true;
    long start = System.nanoTime();
    AuthenticationKeyImportService.ImportSummary summary;
    long totalNanos, allocatedBytes;
    try {
      summary = service.importXlsx(file, null);
      totalNanos = System.nanoTime() - start;
      allocatedBytes = allocated() - allocatedBefore;
    } finally {
      counts.enabled = false;
      heap.stop();
    }
    long gcCount = gcCount() - gcCountBefore, gcMs = gcMillis() - gcMsBefore;
    long serverInserts = scalar(context.pool, INSERT_COUNT) - serverBefore;
    assertExactValues(context.pool, rows);
    int batches = (rows + batch - 1) / batch;
    require(summary.readRows() == rows && summary.insertedCount() == rows, "Incorrect row counts");
    require(counts.commits == 1 && counts.rollbacks == 0 && counts.prepares == 1
        && counts.singleExecutes == 0 && counts.addBatch == rows && counts.executeBatch == batches
        && counts.successfulBatches == batches && serverInserts == batches,
        "Unexpected SQL/transaction pattern for rows=" + rows + " batch=" + batch);
    String line = String.format(Locale.ROOT,
        "%s,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%d,%d,%.6f,%d,%d,%d,%d,%d,%d,true,%d,%d,%d,%d,%d,%d,%d,%d",
        phase, fork, round, rows, batch, file.getSize(), counts.writeNanos / 1e6,
        counts.commitNanos / 1e6, (counts.writeNanos + counts.commitNanos) / 1e6,
        summary.parseElapsedMs(), summary.saveElapsedMs(), totalNanos / 1e6, counts.prepares,
        counts.singleExecutes, counts.addBatch, counts.executeBatch, serverInserts, rows,
        counts.commits, counts.rollbacks, gcCount, gcMs, allocatedBytes, counts.writeAllocated,
        heapBefore, heap.peak.get());
    System.out.println(line);
    return line;
  }

  private static String verifyRollback(int fork, int batch, MockMultipartFile file,
      AuthenticationKeyImportService service, Context context) throws Exception {
    String rejected = String.format(Locale.ROOT, "%05d", batch + batch / 2);
    execute(context.pool, "TRUNCATE TABLE authentication_key");
    execute(context.pool, "INSERT INTO authentication_key (authentication_key,level) VALUES ('ZZZZZ',7)");
    execute(context.pool, "ALTER TABLE authentication_key ADD CONSTRAINT benchmark_reject_key "
        + "CHECK (authentication_key <> '" + rejected + "')");
    Counts counts = context.counts;
    try {
      counts.reset();
      counts.enabled = true;
      Exception failure = null;
      try { service.importXlsx(file, null); }
      catch (Exception exception) { failure = exception; }
      finally { counts.enabled = false; }
      require(failure != null, "Missing expected constraint error");
      boolean expected = false;
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof SQLException sql && sql.getErrorCode() == 3819
            && sql.getMessage().contains("benchmark_reject_key")) expected = true;
      }
      require(expected && counts.commits == 0 && counts.rollbacks == 1
          && counts.successfulBatches == 1 && counts.executeBatch == 2, "Invalid failure/rollback: " + failure);
      require(scalar(context.pool, "SELECT COUNT(*) FROM authentication_key") == 1
          && scalar(context.pool, "SELECT COUNT(*) FROM authentication_key "
              + "WHERE id=1 AND authentication_key='ZZZZZ' AND level=7") == 1, "Partial writes survived");
      return fork + "," + batch + "," + (2 * batch + 1) + "," + rejected + ",1,2,0,1,1,3819,true";
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

  private static MockMultipartFile input(Path output, int rows) throws Exception {
    Path directory = output.getParent().resolve("inputs");
    Files.createDirectories(directory);
    Path path = directory.resolve("keys-" + rows + ".xlsx");
    if (!Files.exists(path)) {
      SXSSFWorkbook workbook = new SXSSFWorkbook(null, 100, true, true);
      try (workbook; OutputStream stream = Files.newOutputStream(path)) {
        Sheet sheet = workbook.createSheet("keys");
        for (int row = 0; row < rows; row++) {
          sheet.createRow(row).createCell(0).setCellValue(String.format(Locale.ROOT, "%05d", row));
        }
        workbook.write(stream);
      } finally { workbook.dispose(); }
    }
    byte[] bytes = Files.readAllBytes(path);
    Files.writeString(output.resolve("input-sha256.txt"),
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            + "  " + path.getFileName() + "\n",
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    return new MockMultipartFile("file", path.getFileName().toString(),
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
  }

  private static Connection observe(Connection connection, Counts counts) {
    return (Connection) Proxy.newProxyInstance(ExcelBatchSizeBenchmark.class.getClassLoader(),
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
            if (counts.enabled) counts.prepares++;
            return Proxy.newProxyInstance(ExcelBatchSizeBenchmark.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                  String name = statementMethod.getName();
                  if (counts.enabled) {
                    switch (name) {
                      case "execute", "executeUpdate", "executeLargeUpdate" -> counts.singleExecutes++;
                      case "addBatch" -> counts.addBatch++;
                      case "executeBatch", "executeLargeBatch" -> counts.executeBatch++;
                    }
                  }
                  Object returned = invoke(statementMethod, result, statementArgs);
                  if (counts.enabled && (name.equals("executeBatch") || name.equals("executeLargeBatch"))) {
                    counts.successfulBatches++;
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

  private static void execute(DataSource pool, String sql) throws Exception {
    try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long scalar(DataSource pool, String sql) throws Exception {
    try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  private static long allocated() { return ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().getId()); }
  private static long gcCount() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(java.lang.management.GarbageCollectorMXBean::getCollectionCount).filter(v -> v >= 0).sum();
  }
  private static long gcMillis() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(java.lang.management.GarbageCollectorMXBean::getCollectionTime).filter(v -> v >= 0).sum();
  }

  private static final class HeapSampler implements AutoCloseable {
    final AtomicLong peak = new AtomicLong();
    volatile boolean enabled;
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "benchmark-heap-sampler");
      thread.setDaemon(true);
      return thread;
    });
    HeapSampler() { scheduler.scheduleAtFixedRate(this::sample, 0, 10, TimeUnit.MILLISECONDS); }
    long start() {
      long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
      peak.set(used);
      enabled = true;
      return used;
    }
    void sample() {
      if (enabled) peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
    }
    void stop() { sample(); enabled = false; }
    @Override public void close() { enabled = false; scheduler.shutdownNow(); }
  }

  private static void writeEnvironment(Path output, int fork, DataSource pool) throws Exception {
    List<String> lines = new ArrayList<>(List.of(
        "java=" + System.getProperty("java.runtime.version"),
        "os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"),
        "jvm_arguments=" + ManagementFactory.getRuntimeMXBean().getInputArguments(),
        "fork=" + fork, "hibernate=" + Version.getVersionString(),
        "spring=" + org.springframework.core.SpringVersion.getVersion(),
        "spring_data_jpa=" + AuthenticationKeyRepository.class.getInterfaces()[0].getPackage().getImplementationVersion(),
        "poi=" + org.apache.poi.Version.getVersion(), "jdbc_url=" + URL,
        "batch_sizes=" + Arrays.toString(BATCHES), "sizes=" + Arrays.toString(SIZES),
        "warmups=2 x 100000 per candidate per JVM; measured_rounds=" + ROUNDS,
        "concurrent_imports=1; hikari_maximum=2; hikari_minimum_idle=1; transaction=one per file",
        "primary_metric=JdbcTemplate.batchUpdate wall time + Connection.commit wall time",
        "heap_sampling_interval_ms=10; heap_is_whole_jvm=true; explicit_gc=false",
        "allocation=main thread cumulative allocated bytes (not retained memory)",
        "correctness=exact keys, null level, positive DB IDs; partial tail=1 row; second batch fails"
    ));
    try (Connection connection = pool.getConnection()) {
      lines.add("mysql=" + connection.getMetaData().getDatabaseProductVersion());
      lines.add("driver=" + connection.getMetaData().getDriverVersion());
      try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
          "SHOW VARIABLES WHERE Variable_name IN ('innodb_buffer_pool_size', 'innodb_redo_log_capacity',"
              + " 'innodb_flush_log_at_trx_commit', 'log_bin', 'performance_schema', 'sql_mode',"
              + " 'transaction_isolation', 'max_allowed_packet', 'character_set_server', 'collation_server')")) {
        while (result.next()) lines.add(result.getString(1) + "=" + result.getString(2));
      }
      try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SHOW CREATE TABLE authentication_key")) {
        result.next();
        lines.add("schema=" + result.getString(2));
      }
    }
    Files.write(output.resolve("environment.txt"), lines);
  }

  private static final class Counts {
    boolean enabled;
    long prepares, singleExecutes, addBatch, executeBatch, successfulBatches, commits, rollbacks;
    long writeNanos, commitNanos, writeAllocated;
    void reset() {
      prepares = singleExecutes = addBatch = executeBatch = successfulBatches = commits = rollbacks = 0;
      writeNanos = commitNanos = writeAllocated = 0;
    }
  }
}
