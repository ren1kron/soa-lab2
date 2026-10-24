package lab2.organizations.web.xml;

import java.io.*;
import java.util.*;
import javax.xml.stream.*;

public final class XmlResponseWriter {
    private XmlResponseWriter() {}

    public static byte[] write(Object body, String root) {
        try {
            var out = new ByteArrayOutputStream();
            var w = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
            w.writeStartDocument("UTF-8", "1.0");
            writeElement(w, root, body);
            w.writeEndDocument();
            w.close();
            return out.toByteArray();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Cannot encode XML", e);
        }
    }

    private static void writeElement(XMLStreamWriter w, String name, Object value)
            throws XMLStreamException {
        if (value == null) return;
        w.writeStartElement(name);
        if (value instanceof Collection<?> list) {
            String item =
                    switch (name) {
                        case "organizations" -> "organization";
                        case "employees" -> "employee";
                        case "details" -> "detail";
                        default -> "item";
                    };
            for (var v : list) writeElement(w, item, v);
        } else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet())
                writeElement(w, entry.getKey().toString(), entry.getValue());
        } else if (value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>
                || value instanceof java.time.temporal.TemporalAccessor)
            w.writeCharacters(value.toString());
        else
            for (var entry : XmlModelMapper.map(value).entrySet())
                writeElement(w, entry.getKey(), entry.getValue());
        w.writeEndElement();
    }
}
