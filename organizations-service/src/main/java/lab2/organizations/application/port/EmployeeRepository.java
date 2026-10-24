package lab2.organizations.application.port;

import java.util.List;
import lab2.organizations.generated.model.Employee;
import lab2.organizations.generated.model.EmployeeWrite;

public interface EmployeeRepository {
    Employee find(long id);

    List<Employee> findAll();

    Employee insert(EmployeeWrite input);

    Employee replace(long id, EmployeeWrite input);

    void delete(long id);

    boolean existsByOrganization(long organizationId);
}
