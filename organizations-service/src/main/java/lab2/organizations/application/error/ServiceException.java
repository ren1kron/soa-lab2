package lab2.organizations.application.error;

/** Application failure; the HTTP adapter chooses the response status and shape. */
public final class ServiceException extends RuntimeException {
    public enum Kind {
        NOT_FOUND,
        CONFLICT,
        INTERNAL_ERROR
    }

    public final Kind kind;
    public final String code;

    public ServiceException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static ServiceException notFound() {
        return new ServiceException(
                Kind.NOT_FOUND, "NOT_FOUND", "Organization, employee, or employer was not found");
    }

    public static ServiceException organizationNotEmpty() {
        return new ServiceException(
                Kind.CONFLICT,
                "ORGANIZATION_NOT_EMPTY",
                "Cannot delete an organization with employees");
    }
}
