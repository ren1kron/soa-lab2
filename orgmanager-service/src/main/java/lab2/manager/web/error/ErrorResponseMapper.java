package lab2.manager.web.error;

import java.util.List;
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
        String message =
                switch (error.kind) {
                    case INTERNAL_ERROR ->
                            "Не удалось завершить запрос. Если вы изменяли данные, проверьте, применились ли изменения";
                    case INTEGRATION_FAILURE ->
                            "Менеджер сформировал запрос, не соответствующий контракту первого сервиса";
                    case UPSTREAM_INTERNAL_ERROR ->
                            "В первом сервисе произошла ошибка. Если вы изменяли данные, проверьте, применились ли изменения";
                    case INVALID_UPSTREAM_RESPONSE ->
                            "Первый сервис вернул некорректный ответ. Проверьте состояние данных, прежде чем повторять изменение";
                    case SERVICE_UNAVAILABLE ->
                            "Сервис временно недоступен. Попробуйте получить данные позже";
                    case DEPENDENCY_UNAVAILABLE ->
                            "Не удалось связаться с первым сервисом. Проверьте состояние данных, прежде чем повторять изменение";
                    case UPSTREAM_TIMEOUT ->
                            "Первый сервис не ответил вовремя. Неизвестно, применилось ли последнее изменение";
                    case NOT_FOUND, CONFLICT -> null;
                };
        if (message != null) {
            return new lab2.manager.generated.model.Error(error.kind.name(), message, List.of());
        }
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
