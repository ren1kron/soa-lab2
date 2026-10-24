package lab2.manager.web.error;

import lab2.manager.application.error.ServiceException;
import lab2.manager.generated.model.ErrorDetail;

public final class ErrorResponseMapper {
    private ErrorResponseMapper() {}

    public static int status(ServiceException error) {
        return switch (error.kind) {
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case INVALID_UPSTREAM_RESPONSE -> 502;
            case SERVICE_UNAVAILABLE, DEPENDENCY_UNAVAILABLE -> 503;
            case UPSTREAM_TIMEOUT -> 504;
            case INTERNAL_ERROR, INTEGRATION_FAILURE, UPSTREAM_INTERNAL_ERROR -> 500;
        };
    }

    public static lab2.manager.generated.model.Error body(ServiceException error) {
        var details =
                error.details.stream()
                        .map(
                                detail ->
                                        new ErrorDetail(
                                                ErrorDetail.LocationEnum.fromValue(
                                                        detail.location()),
                                                detail.field(),
                                                detail.code(),
                                                detail.message()))
                        .toList();
        return new lab2.manager.generated.model.Error(error.code, error.getMessage(), details);
    }
}
