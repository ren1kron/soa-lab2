package lab2.manager.web.error;

import java.util.List;
import java.util.regex.Pattern;
import lab2.manager.generated.model.ErrorDetail;

public final class RequestException extends RuntimeException {
    private static final Pattern INVALID_XML_CHARACTERS =
            Pattern.compile("[^\t\n\r\\x{20}-\\x{D7FF}\\x{E000}-\\x{FFFD}\\x{10000}-\\x{10FFFF}]");

    public final int status;
    public final String code;
    public final List<ErrorDetail> details;

    public RequestException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public RequestException(int status, String code, String message, List<ErrorDetail> details) {
        super(xmlText(message));
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    public lab2.manager.generated.model.Error body() {
        return new lab2.manager.generated.model.Error(code, getMessage(), details);
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

    public static RequestException notAcceptable() {
        return new RequestException(
                406,
                "NOT_ACCEPTABLE",
                "Доступный формат ответа — application/xml",
                List.of(
                        detail(
                                "header",
                                "Accept",
                                "UNSUPPORTED_MEDIA_TYPE",
                                "Укажите application/xml или подходящую маску типа в Accept")));
    }
}
