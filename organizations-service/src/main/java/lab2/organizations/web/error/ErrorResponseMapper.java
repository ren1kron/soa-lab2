package lab2.organizations.web.error;

import java.util.List;
import lab2.organizations.application.error.ServiceException;

public final class ErrorResponseMapper {
    private ErrorResponseMapper() {}

    public static int status(ServiceException error) {
        return switch (error.kind) {
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case INTERNAL_ERROR -> 500;
        };
    }

    public static lab2.organizations.generated.model.Error body(ServiceException error) {
        return new lab2.organizations.generated.model.Error(
                error.code, error.getMessage(), List.of());
    }
}
