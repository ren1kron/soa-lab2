package lab2.manager.infrastructure.postgres;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import lab2.manager.application.acquisition.AcquisitionSnapshot;
import lab2.manager.application.error.ServiceException;
import lab2.manager.application.port.AcquisitionJournal;

public final class PostgresAcquisitionJournal implements AcquisitionJournal {
    private final DataSource source;
    private final AcquisitionSnapshotSerializer serializer = new AcquisitionSnapshotSerializer();

    public PostgresAcquisitionJournal(DataSource source) {
        this.source = source;
    }

    @Override
    public Lease lock(long acquirer, long acquired) {
        Connection connection = null;
        try {
            connection = source.getConnection();
            try (var stmt = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                stmt.setLong(1, acquired);
                try (var rs = stmt.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        connection.close();
                        throw new ServiceException(
                                "CONCURRENT_MODIFICATION",
                                "Another acquisition is active for this source organization");
                    }
                }
            }
            return new HeldLease(connection, acquirer, acquired, serializer);
        } catch (SQLException e) {
            if (connection != null)
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            throw failure(e);
        }
    }

    private static ServiceException failure(Exception e) {
        java.util.logging.Logger.getLogger(PostgresAcquisitionJournal.class.getName())
                .log(java.util.logging.Level.SEVERE, "Acquisition journal failed", e);
        return new ServiceException(
                "INTERNAL_ERROR",
                "Acquisition progress could not be accessed; check current state before continuing");
    }

    private static final class HeldLease implements Lease {
        private final Connection connection;
        private final long acquirer, acquired;
        private final AcquisitionSnapshotSerializer serializer;

        HeldLease(
                Connection connection,
                long acquirer,
                long acquired,
                AcquisitionSnapshotSerializer serializer) {
            this.connection = connection;
            this.acquirer = acquirer;
            this.acquired = acquired;
            this.serializer = serializer;
        }

        @Override
        public AcquisitionSnapshot load() {
            try (var stmt =
                    connection.prepareStatement(
                            "SELECT acquirer_id,employee_ids::text FROM acquisition_journal WHERE source_id=?")) {
                stmt.setLong(1, acquired);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) return null;
                    if (rs.getLong(1) != acquirer)
                        throw new ServiceException(
                                "CONCURRENT_MODIFICATION",
                                "An unfinished acquisition exists with another acquirer");
                    return new AcquisitionSnapshot(
                            acquirer, acquired, serializer.deserialize(rs.getString(2)));
                }
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                throw failure(e);
            }
        }

        @Override
        public void save(List<Long> ids) {
            try (var stmt =
                    connection.prepareStatement(
                            "INSERT INTO acquisition_journal(source_id,acquirer_id,employee_ids) VALUES (?,?,?::jsonb)")) {
                stmt.setLong(1, acquired);
                stmt.setLong(2, acquirer);
                stmt.setString(3, serializer.serialize(ids));
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw failure(e);
            }
        }

        @Override
        public void complete() {
            try (var stmt =
                    connection.prepareStatement(
                            "DELETE FROM acquisition_journal WHERE source_id=? AND acquirer_id=?")) {
                stmt.setLong(1, acquired);
                stmt.setLong(2, acquirer);
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw failure(e);
            }
        }

        @Override
        public void close() {
            try {
                try (var stmt = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    stmt.setLong(1, acquired);
                    stmt.execute();
                }
            } catch (SQLException e) {
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException ignored) {
                }
            } finally {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }
}
