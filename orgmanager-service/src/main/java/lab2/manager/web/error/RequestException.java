package lab2.manager.web.error;

import java.util.List;
import lab2.manager.generated.model.ErrorDetail;

public final class RequestException extends RuntimeException {
    public final int status;
    public final String code;
    public final List<ErrorDetail> details;

    public RequestException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public RequestException(int status, String code, String message, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    public lab2.manager.generated.model.Error body() {
        return new lab2.manager.generated.model.Error(code, getMessage(), details);
    }

    public static ErrorDetail detail(String location, String field, String code, String message) {
        return new ErrorDetail(ErrorDetail.LocationEnum.fromValue(location), field, code, message);
    }
}
