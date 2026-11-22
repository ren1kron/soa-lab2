package lab2.manager.application.error;

import java.util.List;

/** Failures carry application meaning, while adapters own their protocol mapping. */
public final class ServiceException extends RuntimeException {
    public enum Kind {
        NOT_FOUND,
        CONFLICT,
        INTERNAL_ERROR,
        INTEGRATION_FAILURE,
        UPSTREAM_INTERNAL_ERROR,
        INVALID_UPSTREAM_RESPONSE,
        SERVICE_UNAVAILABLE,
        DEPENDENCY_UNAVAILABLE,
        UPSTREAM_TIMEOUT
    }

    public record Detail(String location, String field, String code, String message) {}

    public final Kind kind;
    public final String code;
    public final List<Detail> details;
    public final String retryAfterSeconds;

    public ServiceException(String code, String message) {
        this(kindFor(code), code, message, List.of());
    }

    public ServiceException(Kind kind, String code, String message, List<Detail> details) {
        this(kind, code, message, details, null);
    }

    private ServiceException(
            Kind kind,
            String code,
            String message,
            List<Detail> details,
            String retryAfterSeconds) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.details = List.copyOf(details);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    private static Kind kindFor(String code) {
        return switch (code) {
            case "NOT_FOUND" -> Kind.NOT_FOUND;
            case "ORGANIZATION_NOT_EMPTY",
                            "EMPLOYEE_ALREADY_ASSIGNED",
                            "SELF_ACQUISITION",
                            "CONCURRENT_MODIFICATION" ->
                    Kind.CONFLICT;
            case "INTEGRATION_FAILURE" -> Kind.INTEGRATION_FAILURE;
            case "UPSTREAM_INTERNAL_ERROR" -> Kind.UPSTREAM_INTERNAL_ERROR;
            case "INVALID_UPSTREAM_RESPONSE" -> Kind.INVALID_UPSTREAM_RESPONSE;
            case "SERVICE_UNAVAILABLE" -> Kind.SERVICE_UNAVAILABLE;
            case "DEPENDENCY_UNAVAILABLE" -> Kind.DEPENDENCY_UNAVAILABLE;
            case "UPSTREAM_TIMEOUT" -> Kind.UPSTREAM_TIMEOUT;
            default -> Kind.INTERNAL_ERROR;
        };
    }

    public static ServiceException upstream() {
        return new ServiceException(
                "INVALID_UPSTREAM_RESPONSE",
                "The first service returned an invalid response; check state before repeating a change");
    }

    public static ServiceException upstreamUnavailable(String retryAfterSeconds) {
        return new ServiceException(
                Kind.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                "The first service is temporarily unavailable",
                List.of(),
                retryAfterSeconds);
    }

    public static ServiceException concurrentModification() {
        return new ServiceException(
                "CONCURRENT_MODIFICATION",
                "Работодатель сотрудника изменился во время поглощения. Проверьте его текущее назначение");
    }

    public static ServiceException differentAcquirer() {
        return new ServiceException(
                "CONCURRENT_MODIFICATION",
                "Есть незавершённая операция поглощения с другой поглощающей организацией");
    }
}
