package lab2.organizations.application.organization;

import java.time.Clock;
import java.time.LocalDate;
import lab2.organizations.application.error.ServiceException;
import lab2.organizations.application.port.*;
import lab2.organizations.application.query.*;
import lab2.organizations.generated.model.*;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class OrganizationService {
    private final OrganizationRepository organizations;
    private final EmployeeRepository employees;
    private final WriteTransaction writes;
    private final Clock clock;

    public Organization create(OrganizationWrite input) {
        return writes.execute(() -> organizations.insert(input, LocalDate.now(clock)));
    }

    public Organization get(long id) {
        return organizations.find(id);
    }

    public Organization replace(long id, OrganizationWrite input) {
        return writes.execute(
                () -> organizations.replace(id, input, organizations.find(id).getCreationDate()));
    }

    public void delete(long id) {
        writes.execute(
                () -> {
                    deleteEmpty(id);
                    return null;
                });
    }

    public void deleteByType(OrganizationType type) {
        writes.execute(
                () -> {
                    long id =
                            organizations
                                    .firstIdByType(type)
                                    .orElseThrow(ServiceException::notFound);
                    deleteEmpty(id);
                    return null;
                });
    }

    private void deleteEmpty(long id) {
        organizations.find(id);
        if (employees.existsByOrganization(id)) throw ServiceException.organizationNotEmpty();
        organizations.delete(id);
    }

    public long countByTypeGreaterThan(OrganizationType type) {
        return organizations.findAll().stream()
                .filter(
                        organization ->
                                organization.getType() != null
                                        && organization.getType().ordinal() > type.ordinal())
                .count();
    }

    public PageResult<Organization> list(CollectionQuery query) {
        return query.apply(organizations.findAll(), OrganizationService::field);
    }

    private static Object field(Organization organization, String path) {
        var address = organization.getOfficialAddress();
        var town = address == null ? null : address.getTown();
        return switch (path) {
            case "id" -> organization.getId();
            case "name" -> organization.getName();
            case "coordinates.x" -> organization.getCoordinates().getX();
            case "coordinates.y" -> organization.getCoordinates().getY();
            case "creationDate" -> organization.getCreationDate();
            case "employeeCount" -> organization.getEmployeeCount();
            case "annualTurnover" -> organization.getAnnualTurnover();
            case "type" -> organization.getType();
            case "officialAddress" -> address;
            case "officialAddress.zipCode" -> address == null ? null : address.getZipCode();
            case "officialAddress.town" -> town;
            case "officialAddress.town.x" -> town == null ? null : town.getX();
            case "officialAddress.town.y" -> town == null ? null : town.getY();
            case "officialAddress.town.name" -> town == null ? null : town.getName();
            default -> throw new IllegalArgumentException("Unknown parsed field: " + path);
        };
    }
}
