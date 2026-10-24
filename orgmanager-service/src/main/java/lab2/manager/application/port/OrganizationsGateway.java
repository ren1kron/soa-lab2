package lab2.manager.application.port;

import java.util.List;
import lab2.manager.generated.upstream.model.Employee;
import lab2.manager.generated.upstream.model.Organization;

public interface OrganizationsGateway {
    Organization organization(long id);

    Employee employee(long id);

    Employee assign(Employee employee, long organizationId);

    List<Long> employeeIds(long organizationId);

    long headcount(long organizationId);

    void deleteOrganization(long id);
}
