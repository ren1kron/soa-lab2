package lab2.manager.config;

import com.zaxxer.hikari.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

@ApplicationScoped
public class DatabaseConfiguration {
    @Produces
    @ApplicationScoped
    public DataSource dataSource() {
        var config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(required("DATABASE_URL"));
        config.setUsername(required("DATABASE_USER"));
        config.setPassword(required("DATABASE_PASSWORD"));
        config.setMaximumPoolSize(10);
        var source = new HikariDataSource(config);
        try {
            Flyway.configure().dataSource(source).load().migrate();
            return source;
        } catch (RuntimeException error) {
            source.close();
            throw error;
        }
    }

    public void close(@Disposes DataSource source) {
        ((HikariDataSource) source).close();
    }

    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isEmpty())
            throw new IllegalStateException("Missing configuration: " + key);
        return value;
    }
}
