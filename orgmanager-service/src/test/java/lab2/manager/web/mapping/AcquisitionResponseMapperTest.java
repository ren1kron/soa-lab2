package lab2.manager.web.mapping;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import javax.xml.parsers.DocumentBuilderFactory;
import lab2.manager.application.acquisition.AcquisitionOutcome;
import lab2.manager.generated.upstream.model.*;
import lab2.manager.web.xml.ManagerResponseWriter;
import org.junit.jupiter.api.Test;

class AcquisitionResponseMapperTest {
    private Organization organization() {
        return new Organization(
                Long.MAX_VALUE,
                " exact name ",
                new Coordinates(Long.MIN_VALUE, Integer.MAX_VALUE),
                LocalDate.of(2026, 10, 24),
                0L);
    }

    @Test
    void preservesNestedFieldsAndFullWidthIntegersAcrossContracts() {
        var input =
                organization()
                        .employeeCount(17L)
                        .annualTurnover(42.5)
                        .type(OrganizationType.PUBLIC)
                        .officialAddress(
                                new Address("1234567")
                                        .town(
                                                new Location(
                                                        Long.MIN_VALUE, Long.MAX_VALUE, "Town")));
        var response =
                AcquisitionResponseMapper.response(
                        new AcquisitionOutcome(input, Long.MAX_VALUE - 1, Long.MAX_VALUE));
        var output = response.getOrganization();
        assertEquals(Long.MAX_VALUE - 1, response.getAcquiredId());
        assertEquals(Long.MAX_VALUE, response.getEmployeeCount());
        assertEquals(Long.MAX_VALUE, output.getEmployeeCount());
        assertEquals(input.getId(), output.getId());
        assertEquals(input.getName(), output.getName());
        assertEquals(input.getCreationDate(), output.getCreationDate());
        assertEquals(input.getAnnualTurnover(), output.getAnnualTurnover());
        assertEquals(input.getType().toString(), output.getType().toString());
        assertEquals(Long.MIN_VALUE, output.getCoordinates().getX());
        assertEquals(Integer.MAX_VALUE, output.getCoordinates().getY());
        assertEquals("1234567", output.getOfficialAddress().getZipCode());
        assertEquals(Long.MIN_VALUE, output.getOfficialAddress().getTown().getX());
        assertEquals(Long.MAX_VALUE, output.getOfficialAddress().getTown().getY());
        assertEquals("Town", output.getOfficialAddress().getTown().getName());
    }

    @Test
    void preservesCarriageReturnsInOrganizationXml() throws Exception {
        String name = " North\r\n& <South>\r ";
        var result =
                AcquisitionResponseMapper.response(
                        new AcquisitionOutcome(organization().name(name), 2, 0));
        var xml = ManagerResponseWriter.write(result, "acquisition");
        var document =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(new ByteArrayInputStream(xml));
        assertEquals(name, document.getElementsByTagName("name").item(0).getTextContent());
    }

    @Test
    void preservesAbsentOptionalObjectsAndFields() {
        var input = organization();
        var output =
                AcquisitionResponseMapper.response(new AcquisitionOutcome(input, 2, 0))
                        .getOrganization();
        assertNull(output.getAnnualTurnover());
        assertNull(output.getType());
        assertNull(output.getOfficialAddress());
        assertEquals(0L, output.getEmployeeCount());
        input.officialAddress(new Address("1234567"));
        output =
                AcquisitionResponseMapper.response(new AcquisitionOutcome(input, 2, 0))
                        .getOrganization();
        assertNull(output.getOfficialAddress().getTown());
    }
}
