package lab2.organizations.web.controller;

import java.math.BigInteger;
import java.util.*;
import lab2.organizations.application.employee.EmployeeService;
import lab2.organizations.generated.api.EmployeesApi;
import lab2.organizations.generated.model.*;
import lab2.organizations.web.validation.CollectionQueryResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class EmployeesController implements EmployeesApi {
    private final EmployeeService service;
    private final CollectionQueryResolver queries;

    public EmployeesController(EmployeeService service, CollectionQueryResolver queries) {
        this.service = service;
        this.queries = queries;
    }

    @Override
    public ResponseEntity<Employee> createEmployee(EmployeeWrite input) {
        return ResponseEntity.status(201).body(service.create(input));
    }

    @Override
    public ResponseEntity<Employee> getEmployee(Long employeeId) {
        return ResponseEntity.ok(service.get(employeeId));
    }

    @Override
    public ResponseEntity<Employee> updateEmployee(Long employeeId, EmployeeWrite input) {
        return ResponseEntity.ok(service.replace(employeeId, input));
    }

    @Override
    public ResponseEntity<Void> deleteEmployee(Long employeeId) {
        service.delete(employeeId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<Employee>> listEmployees(
            BigInteger page, BigInteger size, Set<String> sort, List<String> filter) {
        return CollectionResponses.page(service.list(queries.currentQuery()));
    }
}
