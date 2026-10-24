package lab2.organizations.web.xml;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.*;

final class XmlModelMapper {
    private XmlModelMapper() {}

    static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    static Map<String, Object> map(Object value) {
        return JSON.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
    }
}
