package lab2.organizations.infrastructure.postgres;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import javax.sql.DataSource;
import lab2.organizations.application.error.ServiceException;
import lab2.organizations.application.port.OrganizationRepository;
import lab2.organizations.generated.model.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcOrganizationRepository implements OrganizationRepository {
    private static final String COLUMNS =
            "id,name,coordinates_x,coordinates_y,creation_date,annual_turnover,type,address_zip_code,town_x,town_y,town_name,"
                    + "(SELECT COUNT(*) FROM employees WHERE organization_id=organizations.id) AS employee_count";
    private static final OrganizationRowMapper ROWS = new OrganizationRowMapper();
    private final JdbcTemplate jdbc;

    public JdbcOrganizationRepository(DataSource source) {
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public Organization find(long id) {
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM organizations WHERE id=?", ROWS, id);
        if (rows.isEmpty()) throw ServiceException.notFound();
        return rows.get(0);
    }

    @Override
    public List<Organization> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM organizations", ROWS);
    }

    @Override
    public Organization insert(OrganizationWrite input, LocalDate date) {
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO organizations(name,coordinates_x,coordinates_y,creation_date,annual_turnover,type,address_zip_code,town_x,town_y,town_name)"
                                + " VALUES (?,?,?,?,?,?::organization_type,?,?,?,?) RETURNING id",
                        Long.class,
                        values(input, date));
        return find(id);
    }

    @Override
    public Organization replace(long id, OrganizationWrite input, LocalDate date) {
        var values = values(input, date);
        var parameters = Arrays.copyOf(values, values.length + 1);
        parameters[values.length] = id;
        jdbc.update(
                "UPDATE organizations SET name=?,coordinates_x=?,coordinates_y=?,creation_date=?,annual_turnover=?,type=?::organization_type,"
                        + "address_zip_code=?,town_x=?,town_y=?,town_name=? WHERE id=?",
                parameters);
        return find(id);
    }

    @Override
    public void delete(long id) {
        if (jdbc.update("DELETE FROM organizations WHERE id=?", id) == 0)
            throw ServiceException.notFound();
    }

    @Override
    public OptionalLong firstIdByType(OrganizationType type) {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM organizations WHERE type=?::organization_type ORDER BY id LIMIT 1",
                        Long.class,
                        type.toString());
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.get(0));
    }

    private static Object[] values(OrganizationWrite input, LocalDate date) {
        var address = input.getOfficialAddress();
        var town = address == null ? null : address.getTown();
        return new Object[] {
            input.getName(),
            input.getCoordinates().getX(),
            input.getCoordinates().getY(),
            date,
            input.getAnnualTurnover(),
            input.getType() == null ? null : input.getType().toString(),
            address == null ? null : address.getZipCode(),
            town == null ? null : town.getX(),
            town == null ? null : town.getY(),
            town == null ? null : town.getName()
        };
    }
}
