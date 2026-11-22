package lab2.organizations.web.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lab2.organizations.generated.model.ErrorDetail;
import lab2.organizations.web.error.RequestException;

final class RequestErrors {
    private static final String INVALID_REQUEST = "INVALID_REQUEST";
    private final List<ErrorDetail> details = new ArrayList<>();
    private String code;
    private String message;

    void add(RequestException error) {
        merge(error.code, error.getMessage());
        details.addAll(error.details);
    }

    private void merge(String nextCode, String nextMessage) {
        if (details.isEmpty()) {
            code = nextCode;
            message = nextMessage;
        } else {
            if (!code.equals(nextCode)) code = INVALID_REQUEST;
            if (!message.equals(nextMessage)) message = "Проверьте параметры запроса";
        }
    }

    void add(
            String code,
            String message,
            String location,
            String field,
            String reason,
            String hint) {
        merge(code, message);
        details.add(RequestException.detail(location, field, reason, hint));
    }

    void invalid(String message, String location, String field, String reason, String hint) {
        add(INVALID_REQUEST, message, location, field, reason, hint);
    }

    void checkParameters(Map<String, String[]> parameters, Set<String> allowed) {
        parameters.forEach(
                (name, values) -> {
                    String field = name.isEmpty() ? "$" : name;
                    if (!allowed.contains(name))
                        invalid(
                                "Неизвестный параметр запроса",
                                "query",
                                field,
                                "UNKNOWN_FIELD",
                                "Эта операция не поддерживает параметр "
                                        + field
                                        + ". Удалите его из запроса");
                    if (values.length > 1) {
                        String hint =
                                name.equals("sort")
                                        ? "Используйте sort=name,-id вместо sort=name&sort=-id"
                                        : "Передайте параметр " + field + " один раз";
                        invalid(
                                "Параметр " + field + " передан несколько раз",
                                "query",
                                field,
                                "DUPLICATE_PARAMETER",
                                hint);
                    }
                });
    }

    void requireValid() {
        if (!details.isEmpty()) throw new RequestException(400, code, message, details);
    }
}
