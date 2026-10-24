package lab2.manager.application.hiring;

import static org.junit.jupiter.api.Assertions.*;

import lab2.manager.application.error.ServiceException;
import lab2.manager.generated.upstream.model.*;
import lab2.manager.support.*;
import org.junit.jupiter.api.Test;

class HireEmployeeServiceTest {
    @Test
    void hireIsNoOpForExistingEmployerAndRejectsOtherEmployer() {
        var g = new InMemoryOrganizationsGateway();
        var w = new HireEmployeeService(g);
        g.employees.put(10L, new Employee(10L, "name").organizationId(1L));
        assertEquals(1L, w.hire(1, 10));
        assertEquals(0, g.updates);
        assertEquals(
                "EMPLOYEE_ALREADY_ASSIGNED",
                assertThrows(ServiceException.class, () -> w.hire(2, 10)).code);
    }

    @Test
    void hirePreservesNameAndAssignsAnUnemployedEmployee() {
        var g = new InMemoryOrganizationsGateway();
        g.employees.put(10L, new Employee(10L, " original "));
        assertEquals(1L, new HireEmployeeService(g).hire(1, 10));
        assertEquals(" original ", g.employees.get(10L).getName());
    }
}
