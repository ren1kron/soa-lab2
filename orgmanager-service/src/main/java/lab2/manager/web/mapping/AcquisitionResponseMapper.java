package lab2.manager.web.mapping;

import lab2.manager.application.acquisition.AcquisitionOutcome;
import lab2.manager.generated.model.*;

public final class AcquisitionResponseMapper {
    private AcquisitionResponseMapper() {}

    public static Acquisition response(AcquisitionOutcome outcome) {
        var source = outcome.organization();
        var coordinates =
                new Coordinates(source.getCoordinates().getX(), source.getCoordinates().getY());
        var organization =
                new Organization(
                                source.getId(),
                                source.getName(),
                                coordinates,
                                source.getCreationDate())
                        .annualTurnover(source.getAnnualTurnover());
        if (source.getType() != null)
            organization.type(OrganizationType.fromValue(source.getType().toString()));
        if (source.getOfficialAddress() != null) {
            var sourceAddress = source.getOfficialAddress();
            var address = new Address(sourceAddress.getZipCode());
            if (sourceAddress.getTown() != null) {
                var town = sourceAddress.getTown();
                address.town(new Location(town.getX(), town.getY(), town.getName()));
            }
            organization.officialAddress(address);
        }
        return new Acquisition(organization, outcome.acquiredId(), outcome.employeeCount());
    }
}
