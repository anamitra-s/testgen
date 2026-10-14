package com.company.testgen.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Bootstrap for HikariCP connection pooling and JDBI 3 (§6.14, §9.7).
 * Provides transactional primitives and resource management without ORM indirection.
 */
public class Db implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Db.class);

    private final DataSource dataSource;
    private final Jdbi jdbi;

    public Db(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
        this.jdbi = Jdbi.create(dataSource);

        // Install plugins & row mappers
        this.jdbi.installPlugin(new SqlObjectPlugin());
        this.jdbi.installPlugin(new PostgresPlugin());
        this.jdbi.registerRowMapper(JobRecordMapper.INSTANCE);
    }

    public static Db create(HikariConfig config) {
        log.info("Bootstrapping HikariCP datasource with pool '{}'", config.getPoolName());
        HikariDataSource ds = new HikariDataSource(config);
        return new Db(ds);
    }

    public static Db create(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setPoolName("TestGenPool");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        return create(config);
    }

    public static Db create(DataSource dataSource) {
        return new Db(dataSource);
    }

    public static Db fromEnv() {
        String url = System.getenv("TESTGEN_DB_URL");
        if (url == null || url.isBlank()) {
            url = System.getenv("JDBC_DATABASE_URL");
        }
        if (url == null || url.isBlank()) {
            url = "jdbc:postgresql://localhost:5432/testgen";
        }

        String user = System.getenv("TESTGEN_DB_USER");
        if (user == null || user.isBlank()) {
            user = System.getenv("POSTGRES_USER");
        }
        if (user == null || user.isBlank()) {
            user = "testgen";
        }

        String pass = System.getenv("TESTGEN_DB_PASSWORD");
        if (pass == null) {
            pass = System.getenv("POSTGRES_PASSWORD");
        }
        if (pass == null) {
            pass = "testgen";
        }

        return create(url, user, pass);
    }

    public Jdbi jdbi() {
        return jdbi;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public <R, X extends Exception> R inTransaction(HandleCallback<R, X> callback) throws X {
        return jdbi.inTransaction(callback);
    }

    public <X extends Exception> void useTransaction(HandleConsumer<X> consumer) throws X {
        jdbi.useTransaction(consumer);
    }

    public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
        return jdbi.withHandle(callback);
    }

    public <X extends Exception> void useHandle(HandleConsumer<X> consumer) throws X {
        jdbi.useHandle(consumer);
    }

    public Handle open() {
        return jdbi.open();
    }

    public MigrateResult migrate() {
        log.info("Running Flyway migrations...");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        MigrateResult result = flyway.migrate();
        log.info("Flyway migration completed: {} migrations executed", result.migrationsExecuted);
        return result;
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
                log.info("Db connection pool closed successfully.");
            } catch (Exception e) {
                log.warn("Failed to close datasource cleanly", e);
            }
        }
    }
}
