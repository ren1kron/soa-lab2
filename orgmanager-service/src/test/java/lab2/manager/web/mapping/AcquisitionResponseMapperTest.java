package lab2.manager.web.mapping;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import lab2.manager.application.acquisition.AcquisitionOutcome;
import lab2.manager.generated.upstream.model.*;
import org.junit.jupiter.api.Test;

class AcquisitionResponseMapperTest {
    private Organization organization() {
        return new Organization(
                Long.MAX_VALUE,
                " exact name ",
                new Coordinates(Long.MIN_VALUE, Integer.MAX_VALUE),
                LocalDate.of(2026, 10, 24));
    }

    @Test
    void preservesNestedFieldsAndFullWidthIntegersAcrossContracts() {
        var input =
                organization()
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
    void preservesAbsentOptionalObjectsAndFields() {
        var input = organization();
        var output =
                AcquisitionResponseMapper.response(new AcquisitionOutcome(input, 2, 0))
                        .getOrganization();
        assertNull(output.getAnnualTurnover());
        assertNull(output.getType());
        assertNull(output.getOfficialAddress());
        input.officialAddress(new Address("1234567"));
        output =
                AcquisitionResponseMapper.response(new AcquisitionOutcome(input, 2, 0))
                        .getOrganization();
        assertNull(output.getOfficialAddress().getTown());
    }
}
