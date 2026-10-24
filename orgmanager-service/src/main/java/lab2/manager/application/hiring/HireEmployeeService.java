package lab2.manager.application.hiring;

import lab2.manager.application.error.ServiceException;
import lab2.manager.application.port.OrganizationsGateway;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class HireEmployeeService {
    private final OrganizationsGateway organizations;

    public long hire(long organizationId, long employeeId) {
        organizations.organization(organizationId);
        var employee = organizations.employee(employeeId);
        if (employee.getOrganizationId() != null
                && employee.getOrganizationId() != organizationId) {
            throw new ServiceException(
                    "EMPLOYEE_ALREADY_ASSIGNED",
                    "Employee already belongs to another organization");
        }
        if (employee.getOrganizationId() == null) organizations.assign(employee, organizationId);
        return organizations.headcount(organizationId);
    }
}
