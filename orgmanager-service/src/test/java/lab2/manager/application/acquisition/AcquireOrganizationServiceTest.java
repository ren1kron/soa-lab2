package lab2.manager.application.acquisition;

import static lab2.manager.application.error.ServiceException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import lab2.manager.application.error.ServiceException;
import lab2.manager.generated.upstream.model.*;
import lab2.manager.support.*;
import org.junit.jupiter.api.Test;

class AcquireOrganizationServiceTest {
    @Test
    void acquisitionTransfersEverySavedIdAndPreservesOrganizationFields() {
        var g = new InMemoryOrganizationsGateway();
        var journal = new InMemoryAcquisitionJournal();
        for (long i = 1; i <= 205; i++)
            g.employees.put(i, new Employee(i, "employee" + i).organizationId(2L));
        var result = new AcquireOrganizationService(g, journal).acquire(1, 2);
        assertEquals(205L, result.employeeCount());
        assertEquals("org1", result.organization().getName());
        assertFalse(g.organizations.containsKey(2L));
        assertNull(journal.snapshot);
        assertEquals(
                NOT_FOUND,
                assertThrows(
                                ServiceException.class,
                                () -> new AcquireOrganizationService(g, journal).acquire(1, 2))
                        .kind);
    }

    @Test
    void explicitRetryResumesSnapshotWithoutRepeatingCompletedTransfers() {
        var g = new InMemoryOrganizationsGateway();
        var journal = new InMemoryAcquisitionJournal();
        g.employees.put(10L, new Employee(10L, "a").organizationId(2L));
        g.employees.put(11L, new Employee(11L, "b").organizationId(2L));
        g.failUpdate = 2;
        assertEquals(
                UPSTREAM_TIMEOUT,
                assertThrows(
                                ServiceException.class,
                                () -> new AcquireOrganizationService(g, journal).acquire(1, 2))
                        .kind);
        assertEquals(1L, g.employees.get(10L).getOrganizationId());
        assertEquals(List.of(10L, 11L), journal.snapshot.employeeIds());
        assertEquals(2L, new AcquireOrganizationService(g, journal).acquire(1, 2).employeeCount());
        assertEquals(3, g.updates);
    }

    @Test
    void recoversAfterAnUncertainSuccessfulDeletion() {
        var g = new InMemoryOrganizationsGateway();
        var journal = new InMemoryAcquisitionJournal();
        g.uncertainDelete = true;
        g.employees.put(10L, new Employee(10L, "a").organizationId(2L));
        assertThrows(
                ServiceException.class,
                () -> new AcquireOrganizationService(g, journal).acquire(1, 2));
        assertEquals(1L, new AcquireOrganizationService(g, journal).acquire(1, 2).employeeCount());
    }

    @Test
    void missingSavedEmployeeIsAConflictAndRetainsJournal() {
        var g = new InMemoryOrganizationsGateway();
        var journal = new InMemoryAcquisitionJournal();
        journal.snapshot = new AcquisitionSnapshot(1, 2, List.of(999L));
        assertEquals(
                "CONCURRENT_MODIFICATION",
                assertThrows(
                                ServiceException.class,
                                () -> new AcquireOrganizationService(g, journal).acquire(1, 2))
                        .code);
        assertNotNull(journal.snapshot);
    }

    @Test
    void employeeDeletedBetweenReadAndTransferIsAConflict() {
        var g =
                new InMemoryOrganizationsGateway() {
                    @Override
                    public Employee assign(Employee e, long organizationId) {
                        employees.remove(e.getId());
                        throw new ServiceException(
                                "NOT_FOUND", "Employee disappeared during transfer");
                    }
                };
        g.employees.put(10L, new Employee(10L, "a").organizationId(2L));
        var journal = new InMemoryAcquisitionJournal();
        assertEquals(
                CONFLICT,
                assertThrows(
                                ServiceException.class,
                                () -> new AcquireOrganizationService(g, journal).acquire(1, 2))
                        .kind);
        assertNotNull(journal.snapshot);
    }

    @Test
    void failedFinalReadRetainsSnapshotAndExplicitRetryCompletes() {
        var gateway =
                new InMemoryOrganizationsGateway() {
                    boolean fail = true;

                    @Override
                    public long headcount(long id) {
                        if (id == 1 && fail) {
                            fail = false;
                            throw new ServiceException(
                                    "UPSTREAM_TIMEOUT", "Final count unavailable");
                        }
                        return super.headcount(id);
                    }
                };
        gateway.employees.put(10L, new Employee(10L, "employee").organizationId(2L));
        var journal = new InMemoryAcquisitionJournal();
        var service = new AcquireOrganizationService(gateway, journal);
        assertEquals(
                UPSTREAM_TIMEOUT,
                assertThrows(ServiceException.class, () -> service.acquire(1, 2)).kind);
        assertFalse(gateway.organizations.containsKey(2L));
        assertNotNull(journal.snapshot);
        assertEquals(1, service.acquire(1, 2).employeeCount());
        assertEquals(1, gateway.updates);
        assertNull(journal.snapshot);
    }
}
