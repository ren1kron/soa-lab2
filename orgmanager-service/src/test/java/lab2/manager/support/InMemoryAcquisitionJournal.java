package lab2.manager.support;

import java.util.*;
import lab2.manager.application.acquisition.AcquisitionSnapshot;
import lab2.manager.application.port.*;
import lab2.manager.generated.upstream.model.*;

public class InMemoryAcquisitionJournal implements AcquisitionJournal {
    public AcquisitionSnapshot snapshot;

    public Lease lock(long acquirer, long source) {
        return new Lease() {
            public AcquisitionSnapshot load() {
                return snapshot;
            }

            public void save(List<Long> ids) {
                snapshot = new AcquisitionSnapshot(acquirer, source, List.copyOf(ids));
            }

            public void complete() {
                snapshot = null;
            }

            public void close() {}
        };
    }
}
