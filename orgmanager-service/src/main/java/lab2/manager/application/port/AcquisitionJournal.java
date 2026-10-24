package lab2.manager.application.port;

import java.util.List;
import lab2.manager.application.acquisition.AcquisitionSnapshot;

public interface AcquisitionJournal {
    Lease lock(long acquirerId, long sourceId);

    interface Lease extends AutoCloseable {
        AcquisitionSnapshot load();

        void save(List<Long> employeeIds);

        void complete();

        @Override
        void close();
    }
}
