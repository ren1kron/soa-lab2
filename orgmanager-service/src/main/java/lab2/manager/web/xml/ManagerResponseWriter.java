package lab2.manager.web.xml;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.*;
import java.util.*;
import javax.xml.stream.*;

public final class ManagerResponseWriter {
    private ManagerResponseWriter() {}

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static Map<String, Object> model(Object value) {
        return JSON.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    public static byte[] write(Object value, String root) {
        try {
            var out = new ByteArrayOutputStream();
            var writer = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
            writer.writeStartDocument("UTF-8", "1.0");
            element(writer, root, value);
            writer.writeEndDocument();
            writer.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode XML", e);
        }
    }

    private static void element(XMLStreamWriter w, String name, Object value)
            throws XMLStreamException {
        if (value == null) return;
        w.writeStartElement(name);
        if (value instanceof Collection<?> list)
            for (var child : list) element(w, name.equals("details") ? "detail" : "item", child);
        else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet())
                element(w, entry.getKey().toString(), entry.getValue());
        } else if (value instanceof String
                || value instanceof Number
                || value instanceof Enum<?>
                || value instanceof java.time.temporal.TemporalAccessor)
            w.writeCharacters(value.toString());
        else for (var entry : model(value).entrySet()) element(w, entry.getKey(), entry.getValue());
        w.writeEndElement();
    }
}
