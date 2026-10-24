package lab2.organizations.infrastructure.postgres;

import java.util.function.Supplier;
import javax.sql.DataSource;
import lab2.organizations.application.port.WriteTransaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One shared database transaction covers service checks and all repository writes. */
public final class PostgresWriteTransaction implements WriteTransaction {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresWriteTransaction(DataSource source) {
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Override
    public <T> T execute(Supplier<T> operation) {
        return transactions.execute(
                status -> {
                    jdbc.execute("SELECT pg_advisory_xact_lock(261250)");
                    return operation.get();
                });
    }
}
