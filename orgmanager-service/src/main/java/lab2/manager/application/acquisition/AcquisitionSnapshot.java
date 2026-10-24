package lab2.manager.application.acquisition;

import java.util.List;

public record AcquisitionSnapshot(long acquirerId, long sourceId, List<Long> employeeIds) {
    public AcquisitionSnapshot {
        employeeIds = List.copyOf(employeeIds);
    }
}
