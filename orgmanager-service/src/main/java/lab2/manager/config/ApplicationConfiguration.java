package lab2.manager.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import javax.sql.DataSource;
import lab2.manager.application.acquisition.AcquireOrganizationService;
import lab2.manager.application.hiring.HireEmployeeService;
import lab2.manager.application.port.*;
import lab2.manager.infrastructure.postgres.PostgresAcquisitionJournal;

@ApplicationScoped
public class ApplicationConfiguration {
    @Produces
    @ApplicationScoped
    public AcquisitionJournal acquisitionJournal(DataSource source) {
        return new PostgresAcquisitionJournal(source);
    }

    @Produces
    public HireEmployeeService hiring(OrganizationsGateway organizations) {
        return new HireEmployeeService(organizations);
    }

    @Produces
    public AcquireOrganizationService acquisitions(
            OrganizationsGateway organizations, AcquisitionJournal journal) {
        return new AcquireOrganizationService(organizations, journal);
    }
}
