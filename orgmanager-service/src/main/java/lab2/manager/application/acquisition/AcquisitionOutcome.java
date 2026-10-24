package lab2.manager.application.acquisition;

import lab2.manager.generated.upstream.model.Organization;

public record AcquisitionOutcome(Organization organization, long acquiredId, long employeeCount) {}
