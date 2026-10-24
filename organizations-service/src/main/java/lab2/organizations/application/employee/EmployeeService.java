package lab2.organizations.application.employee;

import lab2.organizations.application.port.*;
import lab2.organizations.application.query.*;
import lab2.organizations.generated.model.*;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class EmployeeService {
    private final EmployeeRepository employees;
    private final OrganizationRepository organizations;
    private final WriteTransaction writes;

    public Employee create(EmployeeWrite input) {
        return writes.execute(
                () -> {
                    checkEmployer(input);
                    return employees.insert(input);
                });
    }

    public Employee get(long id) {
        return employees.find(id);
    }

    public Employee replace(long id, EmployeeWrite input) {
        return writes.execute(
                () -> {
                    employees.find(id);
                    checkEmployer(input);
                    return employees.replace(id, input);
                });
    }

    public void delete(long id) {
        writes.execute(
                () -> {
                    employees.delete(id);
                    return null;
                });
    }

    private void checkEmployer(EmployeeWrite input) {
        if (input.getOrganizationId() != null) organizations.find(input.getOrganizationId());
    }

    public PageResult<Employee> list(CollectionQuery query) {
        return query.apply(
                employees.findAll(),
                (employee, field) ->
                        switch (field) {
                            case "id" -> employee.getId();
                            case "name" -> employee.getName();
                            case "organizationId" -> employee.getOrganizationId();
                            default ->
                                    throw new IllegalArgumentException(
                                            "Unknown parsed field: " + field);
                        });
    }
}
