package lab2.manager.web.error;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import lab2.manager.application.error.ServiceException;
import org.junit.jupiter.api.Test;

class ErrorResponseMapperTest {
    @Test
    void preservesUpstreamFailureMeaningIndependentlyOfPublicCode() {
        var detail =
                new ServiceException.Detail(
                        "body", "organizationId", "MISSING", "Employer is missing");
        var error =
                new ServiceException(
                        ServiceException.Kind.NOT_FOUND, "CUSTOM_CODE", "Missing", List.of(detail));
        assertEquals(404, ErrorResponseMapper.status(error));
        var body = ErrorResponseMapper.body(error);
        assertEquals("CUSTOM_CODE", body.getCode());
        assertEquals("Missing", body.getMessage());
        assertEquals("body", body.getDetails().get(0).getLocation().toString());
        assertEquals("organizationId", body.getDetails().get(0).getField());
        assertEquals("MISSING", body.getDetails().get(0).getCode());
        assertEquals("Employer is missing", body.getDetails().get(0).getMessage());
    }

    @Test
    void mapsDependencyAndIntegrationFailuresToContractStatuses() {
        assertEquals(
                500,
                ErrorResponseMapper.status(
                        new ServiceException("INTEGRATION_FAILURE", "Rejected")));
        assertEquals(502, ErrorResponseMapper.status(ServiceException.upstream()));
        assertEquals(
                503,
                ErrorResponseMapper.status(
                        new ServiceException("DEPENDENCY_UNAVAILABLE", "Unavailable")));
        assertEquals(
                504,
                ErrorResponseMapper.status(
                        new ServiceException("UPSTREAM_TIMEOUT", "Unknown result")));
        assertEquals(
                409,
                ErrorResponseMapper.status(
                        new ServiceException("SELF_ACQUISITION", "Same organization")));
    }
}
