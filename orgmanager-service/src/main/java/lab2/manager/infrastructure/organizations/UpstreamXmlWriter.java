package lab2.manager.infrastructure.organizations;

import java.io.ByteArrayOutputStream;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import lab2.manager.generated.upstream.model.EmployeeWrite;

final class UpstreamXmlWriter {
    private UpstreamXmlWriter() {}

    static byte[] employee(EmployeeWrite input) {
        try {
            var bytes = new ByteArrayOutputStream();
            var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(bytes, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("employee");
            xml.writeStartElement("name");
            writeName(xml, input.getName());
            xml.writeEndElement();
            if (input.getOrganizationId() != null) {
                xml.writeStartElement("organizationId");
                xml.writeCharacters(input.getOrganizationId().toString());
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
            return bytes.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode employee assignment", error);
        }
    }

    private static void writeName(XMLStreamWriter writer, String name) throws XMLStreamException {
        int start = 0;
        int carriageReturn;
        while ((carriageReturn = name.indexOf('\r', start)) >= 0) {
            writer.writeCharacters(name.substring(start, carriageReturn));
            // Preserve the employee name through XML line-ending normalization.
            writer.writeEntityRef("#13");
            start = carriageReturn + 1;
        }
        writer.writeCharacters(name.substring(start));
    }
}
