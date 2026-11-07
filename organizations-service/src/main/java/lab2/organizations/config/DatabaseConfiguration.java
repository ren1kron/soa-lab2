package lab2.organizations.config;

import com.zaxxer.hikari.*;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.*;

@Configuration
public class DatabaseConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource() {
        var config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(required("DATABASE_URL"));
        config.setUsername(required("DATABASE_USER"));
        config.setPassword(required("DATABASE_PASSWORD"));
        config.setMaximumPoolSize(10);
        var source = new HikariDataSource(config);
        try {
            migrate(source, System.getenv("FLYWAY_HISTORY_TABLE"));
            return source;
        } catch (RuntimeException error) {
            source.close();
            throw error;
        }
    }

    static void migrate(DataSource source, String historyTable) {
        var flyway = Flyway.configure().dataSource(source);
        if (historyTable != null && !historyTable.isEmpty()) {
            flyway.table(historyTable).baselineOnMigrate(true).baselineVersion("0");
        }
        flyway.load().migrate();
    }

    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isEmpty())
            throw new IllegalStateException("Missing configuration: " + key);
        return value;
    }
}
