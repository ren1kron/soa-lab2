package lab2.organizations.config;

import java.time.*;
import javax.sql.DataSource;
import lab2.organizations.application.employee.EmployeeService;
import lab2.organizations.application.organization.OrganizationService;
import lab2.organizations.application.port.*;
import lab2.organizations.infrastructure.postgres.*;
import org.springframework.context.annotation.*;

@Configuration
@Import({DatabaseConfiguration.class, WebConfiguration.class})
@ComponentScan("lab2.organizations.web")
public class ApplicationConfiguration {
    @Bean
    public OrganizationRepository organizations(DataSource source) {
        return new JdbcOrganizationRepository(source);
    }

    @Bean
    public EmployeeRepository employees(DataSource source) {
        return new JdbcEmployeeRepository(source);
    }

    @Bean
    public WriteTransaction writes(DataSource source) {
        return new PostgresWriteTransaction(source);
    }

    @Bean
    public Clock applicationClock() {
        return Clock.system(
                ZoneId.of(System.getenv().getOrDefault("APP_TIME_ZONE", "Europe/Moscow")));
    }

    @Bean
    public OrganizationService organizationService(
            OrganizationRepository organizations,
            EmployeeRepository employees,
            WriteTransaction writes,
            Clock clock) {
        return new OrganizationService(organizations, employees, writes, clock);
    }

    @Bean
    public EmployeeService employeeService(
            EmployeeRepository employees,
            OrganizationRepository organizations,
            WriteTransaction writes) {
        return new EmployeeService(employees, organizations, writes);
    }
}
