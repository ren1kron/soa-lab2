package lab2.organizations.config;

import com.zaxxer.hikari.*;
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
            Flyway.configure().dataSource(source).load().migrate();
            return source;
        } catch (RuntimeException error) {
            source.close();
            throw error;
        }
    }

    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isEmpty())
            throw new IllegalStateException("Missing configuration: " + key);
        return value;
    }
}
