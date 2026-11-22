package lab2.organizations.web.error;

import java.util.List;
import java.util.regex.Pattern;
import lab2.organizations.generated.model.ErrorDetail;

public final class RequestException extends RuntimeException {
    private static final Pattern INVALID_XML_CHARACTERS =
            Pattern.compile("[^\t\n\r\\x{20}-\\x{D7FF}\\x{E000}-\\x{FFFD}\\x{10000}-\\x{10FFFF}]");

    public final int status;
    public final String code;
    public final List<ErrorDetail> details;

    public RequestException(int status, String code, String message, List<ErrorDetail> details) {
        super(xmlText(message));
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    public RequestException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public lab2.organizations.generated.model.Error body() {
        return new lab2.organizations.generated.model.Error(code, getMessage(), details);
    }

    public static ErrorDetail detail(String location, String field, String code, String message) {
        return new ErrorDetail(
                ErrorDetail.LocationEnum.fromValue(location),
                xmlText(field),
                code,
                xmlText(message));
    }

    private static String xmlText(String value) {
        return INVALID_XML_CHARACTERS.matcher(value).replaceAll("�");
    }

    public static RequestException invalid(
            String location, String field, String code, String message) {
        return new RequestException(
                400,
                "INVALID_REQUEST",
                "Проверьте параметры запроса",
                List.of(detail(location, field, code, message)));
    }

    public static RequestException missingBody(String root) {
        return new RequestException(
                400,
                "MISSING_BODY",
                "Отсутствует обязательное XML-тело",
                List.of(
                        detail(
                                "body",
                                "$",
                                "REQUIRED",
                                "Передайте XML-тело с корневым элементом " + root)));
    }
}
