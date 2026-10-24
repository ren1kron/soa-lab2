package lab2.manager.application.acquisition;

import java.util.Objects;
import lab2.manager.application.error.ServiceException;
import lab2.manager.application.port.*;
import lab2.manager.generated.upstream.model.Employee;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class AcquireOrganizationService {
    private final OrganizationsGateway organizations;
    private final AcquisitionJournal journal;

    public AcquisitionOutcome acquire(long acquirerId, long sourceId) {
        if (acquirerId == sourceId) {
            throw new ServiceException(
                    "SELF_ACQUISITION", "Acquirer and acquired organizations must differ");
        }
        try (var lease = journal.lock(acquirerId, sourceId)) {
            var snapshot = lease.load();
            organizations.organization(acquirerId);
            if (snapshot == null) {
                organizations.organization(sourceId);
                var ids = organizations.employeeIds(sourceId);
                // This commit precedes every transfer and is not part of a workflow-wide
                // transaction.
                lease.save(ids);
                snapshot = new AcquisitionSnapshot(acquirerId, sourceId, ids);
            } else if (snapshot.acquirerId() != acquirerId) {
                throw conflict();
            }
            boolean sourceExists = sourceExists(sourceId);
            for (long id : snapshot.employeeIds()) {
                Employee employee = savedEmployee(id);
                if (Objects.equals(employee.getOrganizationId(), acquirerId)) continue;
                if (!sourceExists || !Objects.equals(employee.getOrganizationId(), sourceId))
                    throw conflict();
                transfer(employee, acquirerId);
            }
            if (sourceExists) organizations.deleteOrganization(sourceId);
            var outcome =
                    new AcquisitionOutcome(
                            organizations.organization(acquirerId),
                            sourceId,
                            organizations.headcount(acquirerId));
            lease.complete();
            return outcome;
        }
    }

    private boolean sourceExists(long sourceId) {
        try {
            organizations.organization(sourceId);
            return true;
        } catch (ServiceException error) {
            if (error.kind != ServiceException.Kind.NOT_FOUND) throw error;
            return false;
        }
    }

    private Employee savedEmployee(long id) {
        try {
            return organizations.employee(id);
        } catch (ServiceException error) {
            if (error.kind == ServiceException.Kind.NOT_FOUND) throw conflict();
            throw error;
        }
    }

    private void transfer(Employee employee, long acquirerId) {
        try {
            organizations.assign(employee, acquirerId);
        } catch (ServiceException error) {
            // Distinguish a deleted employee from a deleted acquirer without retrying the PUT.
            if (error.kind == ServiceException.Kind.NOT_FOUND) savedEmployee(employee.getId());
            throw error;
        }
    }

    private static ServiceException conflict() {
        return new ServiceException(
                "CONCURRENT_MODIFICATION",
                "A saved employee was removed or reassigned; check current ownership");
    }
}
