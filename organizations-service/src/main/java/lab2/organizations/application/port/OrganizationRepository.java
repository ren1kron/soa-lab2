package lab2.organizations.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import lab2.organizations.generated.model.*;

public interface OrganizationRepository {
    Organization find(long id);

    List<Organization> findAll();

    Organization insert(OrganizationWrite input, LocalDate creationDate);

    Organization replace(long id, OrganizationWrite input, LocalDate creationDate);

    void delete(long id);

    OptionalLong firstIdByType(OrganizationType type);
}
