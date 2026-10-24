package lab2.manager.infrastructure.organizations;

import java.io.ByteArrayOutputStream;
import javax.xml.stream.XMLOutputFactory;
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
            xml.writeCharacters(input.getName());
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
}
