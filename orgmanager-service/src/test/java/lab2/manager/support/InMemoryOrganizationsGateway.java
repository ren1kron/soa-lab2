package lab2.manager.support;

import java.time.LocalDate;
import java.util.*;
import lab2.manager.application.error.ServiceException;
import lab2.manager.application.port.*;
import lab2.manager.generated.upstream.model.*;

public class InMemoryOrganizationsGateway implements OrganizationsGateway {
    public final Map<Long, Organization> organizations = new HashMap<>();
    public final Map<Long, Employee> employees = new LinkedHashMap<>();
    public int updates;
    public int failUpdate = -1;
    public boolean uncertainDelete;

    public InMemoryOrganizationsGateway() {
        organizations.put(1L, org(1));
        organizations.put(2L, org(2));
    }

    public static Organization org(long id) {
        return new Organization(
                id, "org" + id, new Coordinates(1L, 2), LocalDate.of(2026, 10, 24), 0L);
    }

    public Organization organization(long id) {
        if (!organizations.containsKey(id)) throw new ServiceException("NOT_FOUND", "Missing");
        long count =
                employees.values().stream()
                        .filter(employee -> Objects.equals(employee.getOrganizationId(), id))
                        .count();
        return organizations.get(id).employeeCount(count);
    }

    public Employee employee(long id) {
        if (!employees.containsKey(id)) throw new ServiceException("NOT_FOUND", "Missing");
        return employees.get(id);
    }

    public Employee assign(Employee e, long organizationId) {
        updates++;
        if (updates == failUpdate) throw new ServiceException("UPSTREAM_TIMEOUT", "Unknown result");
        var updated = new Employee(e.getId(), e.getName()).organizationId(organizationId);
        employees.put(e.getId(), updated);
        return updated;
    }

    public List<Long> employeeIds(long id) {
        return employees.values().stream()
                .filter(e -> Objects.equals(e.getOrganizationId(), id))
                .map(Employee::getId)
                .toList();
    }

    public long headcount(long id) {
        return employeeIds(id).size();
    }

    public void deleteOrganization(long id) {
        if (headcount(id) > 0) throw new ServiceException("ORGANIZATION_NOT_EMPTY", "Not empty");
        organizations.remove(id);
        if (uncertainDelete) {
            uncertainDelete = false;
            throw new ServiceException("UPSTREAM_TIMEOUT", "Unknown deletion result");
        }
    }
}
