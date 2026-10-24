package lab2.organizations.infrastructure.postgres;

import java.util.List;
import javax.sql.DataSource;
import lab2.organizations.application.error.ServiceException;
import lab2.organizations.application.port.EmployeeRepository;
import lab2.organizations.generated.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcEmployeeRepository implements EmployeeRepository {
    private static final RowMapper<Employee> EMPLOYEE =
            (row, index) ->
                    new Employee(row.getLong(1), row.getString(2))
                            .organizationId((Long) row.getObject(3));
    private final JdbcTemplate jdbc;

    public JdbcEmployeeRepository(DataSource source) {
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public Employee find(long id) {
        var rows =
                jdbc.query(
                        "SELECT id,name,organization_id FROM employees WHERE id=?", EMPLOYEE, id);
        if (rows.isEmpty()) throw ServiceException.notFound();
        return rows.get(0);
    }

    @Override
    public List<Employee> findAll() {
        return jdbc.query("SELECT id,name,organization_id FROM employees", EMPLOYEE);
    }

    @Override
    public Employee insert(EmployeeWrite input) {
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO employees(name,organization_id) VALUES (?,?) RETURNING id",
                        Long.class,
                        input.getName(),
                        input.getOrganizationId());
        return find(id);
    }

    @Override
    public Employee replace(long id, EmployeeWrite input) {
        jdbc.update(
                "UPDATE employees SET name=?,organization_id=? WHERE id=?",
                input.getName(),
                input.getOrganizationId(),
                id);
        return find(id);
    }

    @Override
    public void delete(long id) {
        if (jdbc.update("DELETE FROM employees WHERE id=?", id) == 0)
            throw ServiceException.notFound();
    }

    @Override
    public boolean existsByOrganization(long id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM employees WHERE organization_id=?)",
                        Boolean.class,
                        id));
    }
}
