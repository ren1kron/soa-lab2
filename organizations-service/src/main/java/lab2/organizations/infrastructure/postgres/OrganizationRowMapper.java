package lab2.organizations.infrastructure.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import lab2.organizations.generated.model.*;
import org.springframework.jdbc.core.RowMapper;

final class OrganizationRowMapper implements RowMapper<Organization> {
    @Override
    public Organization mapRow(ResultSet row, int index) throws SQLException {
        var organization =
                new Organization(
                                row.getLong("id"),
                                row.getString("name"),
                                new Coordinates(
                                        row.getLong("coordinates_x"), row.getInt("coordinates_y")),
                                row.getObject("creation_date", LocalDate.class),
                                row.getLong("employee_count"))
                        .annualTurnover(row.getObject("annual_turnover", Double.class));
        String type = row.getString("type");
        if (type != null) organization.type(OrganizationType.fromValue(type));
        String zipCode = row.getString("address_zip_code");
        if (zipCode != null) {
            var address = new Address(zipCode);
            String townName = row.getString("town_name");
            if (townName != null) {
                address.town(new Location(row.getLong("town_x"), row.getLong("town_y"), townName));
            }
            organization.officialAddress(address);
        }
        return organization;
    }
}
