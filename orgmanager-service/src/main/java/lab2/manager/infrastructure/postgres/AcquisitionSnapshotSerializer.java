package lab2.manager.infrastructure.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class AcquisitionSnapshotSerializer {
    private final ObjectMapper json = new ObjectMapper();

    String serialize(List<Long> ids) {
        try {
            return json.writeValueAsString(ids);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode acquisition snapshot", error);
        }
    }

    List<Long> deserialize(String document) {
        try {
            return json.readValue(document, new TypeReference<List<Long>>() {});
        } catch (Exception error) {
            throw new IllegalStateException("Invalid acquisition snapshot", error);
        }
    }
}
