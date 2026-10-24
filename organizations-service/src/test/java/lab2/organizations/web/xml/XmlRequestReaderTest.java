package lab2.organizations.web.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import lab2.organizations.generated.model.EmployeeWrite;
import lab2.organizations.generated.model.OrganizationWrite;
import lab2.organizations.web.error.RequestException;
import org.junit.jupiter.api.Test;

class XmlRequestReaderTest {
    private <T> T read(String xml, Class<T> type) {
        return XmlRequestReader.read(xml.getBytes(StandardCharsets.UTF_8), type);
    }

    @Test
    void preservesWhitespaceNamesAndEmptyZip() {
        var org =
                read(
                        "<organization><name> </name><coordinates><x>-9223372036854775808</x><y>2147483647</y></coordinates><officialAddress><zipCode/></officialAddress></organization>",
                        OrganizationWrite.class);
        assertEquals(" ", org.getName());
        assertEquals(Long.MIN_VALUE, org.getCoordinates().getX());
        assertEquals("", org.getOfficialAddress().getZipCode());
    }

    @Test
    void reportsAllIndependentViolationsBeforeBinding() {
        var e =
                assertThrows(
                        RequestException.class,
                        () ->
                                read(
                                        "<organization><id>2</id><name/><name>x</name><coordinates><y>2</y></coordinates><annualTurnover>0</annualTurnover><extra/></organization>",
                                        OrganizationWrite.class));
        assertEquals(422, e.status);
        assertTrue(e.details.stream().anyMatch(d -> d.getField().equals("coordinates.x")));
        assertTrue(e.details.stream().anyMatch(d -> d.getCode().equals("DUPLICATE_FIELD")));
        assertTrue(e.details.stream().anyMatch(d -> d.getCode().equals("READ_ONLY_FIELD")));
        assertTrue(e.details.stream().anyMatch(d -> d.getCode().equals("OUT_OF_RANGE")));
        assertTrue(e.details.stream().anyMatch(d -> d.getCode().equals("TOO_SHORT")));
        assertTrue(e.details.stream().anyMatch(d -> d.getCode().equals("UNKNOWN_FIELD")));
    }

    @Test
    void distinguishesMalformedXmlFromWrongRoot() {
        assertEquals(
                400,
                assertThrows(RequestException.class, () -> read("<employee>", EmployeeWrite.class))
                        .status);
        assertEquals(
                422,
                assertThrows(
                                RequestException.class,
                                () -> read("<wrong><name>x</name></wrong>", EmployeeWrite.class))
                        .status);
    }

    @Test
    void rejectsEntitiesNilAndNestedScalarElements() {
        for (String xml :
                new String[] {
                    "<!DOCTYPE employee [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><employee><name>&x;</name></employee>",
                    "<employee xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'><name xsi:nil='true'/></employee>",
                    "<employee><name><x>a</x></name></employee>"
                }) {
            assertThrows(RequestException.class, () -> read(xml, EmployeeWrite.class));
        }
    }

    @Test
    void rejectsOutOfRangeAndNonFiniteNumbers() {
        for (String v : new String[] {"NaN", "Infinity", "1e309", "-1", "0"}) {
            assertEquals(
                    422,
                    assertThrows(
                                    RequestException.class,
                                    () ->
                                            read(
                                                    "<organization><name>x</name><coordinates><x>1</x><y>2</y></coordinates><annualTurnover>"
                                                            + v
                                                            + "</annualTurnover></organization>",
                                                    OrganizationWrite.class))
                            .status);
        }
    }
}
