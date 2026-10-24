package lab2.manager.infrastructure.organizations;

import lab2.manager.application.error.ServiceException;

final class UpstreamErrorTranslator {
    private UpstreamErrorTranslator() {}

    static ServiceException translate(int status, byte[] body) {
        var error = UpstreamXmlReader.read(body, "error", lab2.manager.generated.model.Error.class);
        if (status == 404 || status == 409) {
            var details =
                    error.getDetails().stream()
                            .map(
                                    detail ->
                                            new ServiceException.Detail(
                                                    detail.getLocation().toString(),
                                                    detail.getField(),
                                                    detail.getCode(),
                                                    detail.getMessage()))
                            .toList();
            return new ServiceException(
                    status == 404
                            ? ServiceException.Kind.NOT_FOUND
                            : ServiceException.Kind.CONFLICT,
                    error.getCode(),
                    error.getMessage(),
                    details);
        }
        return switch (status) {
            case 400, 415, 422 ->
                    new ServiceException(
                            "INTEGRATION_FAILURE",
                            "Manager request was rejected by the first service");
            case 500 ->
                    new ServiceException(
                            "UPSTREAM_INTERNAL_ERROR",
                            "The first service failed; check state before repeating a change");
            case 503 ->
                    new ServiceException(
                            "SERVICE_UNAVAILABLE",
                            "The first service is temporarily unavailable; check current state before repeating a change");
            case 504 ->
                    new ServiceException(
                            "UPSTREAM_TIMEOUT",
                            "The first service timed out; the last change may have applied");
            default -> ServiceException.upstream();
        };
    }
}
