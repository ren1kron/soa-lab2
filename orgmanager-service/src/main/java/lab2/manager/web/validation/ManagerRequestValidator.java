package lab2.manager.web.validation;

import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import lab2.manager.generated.model.ErrorDetail;
import lab2.manager.web.error.ManagerExceptionMapper;
import lab2.manager.web.error.RequestException;

@Provider
@PreMatching
public class ManagerRequestValidator implements ContainerRequestFilter, ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        if (request.getMethod().equals("OPTIONS") && allowedOrigin(request) != null) {
            request.abortWith(Response.noContent().build());
            return;
        }
        try {
            accept(request.getHeaderString("Accept"));
            String path = request.getUriInfo().getPath();
            if (path.startsWith("/")) path = path.substring(1);
            String[] parts = path.split("/");
            boolean hire =
                    parts.length == 3 && parts[0].equals("orgmanager") && parts[1].equals("hire");
            boolean acquire =
                    parts.length == 4
                            && parts[0].equals("orgmanager")
                            && parts[1].equals("acquise");
            if (!hire && !acquire) return;
            var errors = new ArrayList<ErrorDetail>();
            if (hire) id(parts[2], "path", "id", errors);
            else {
                id(parts[2], "path", "acquirerId", errors);
                id(parts[3], "path", "acquiredId", errors);
            }
            var params = request.getUriInfo().getQueryParameters();
            params.forEach(
                    (key, values) -> {
                        String field = key.isEmpty() ? "$" : key;
                        if (!hire || !key.equals("employeeId"))
                            errors.add(
                                    RequestException.detail(
                                            "query",
                                            field,
                                            "UNKNOWN_FIELD",
                                            "Эта операция не поддерживает параметр "
                                                    + field
                                                    + ". Удалите его из запроса"));
                        if (values.size() != 1)
                            errors.add(
                                    RequestException.detail(
                                            "query",
                                            field,
                                            "DUPLICATE_PARAMETER",
                                            "Передайте параметр " + field + " только один раз"));
                    });
            if (hire) {
                if (!params.containsKey("employeeId"))
                    errors.add(
                            RequestException.detail(
                                    "query",
                                    "employeeId",
                                    "REQUIRED",
                                    "Передайте обязательный параметр employeeId"));
                else id(params.getFirst("employeeId"), "query", "employeeId", errors);
            }
            if (request.getEntityStream().read() != -1)
                errors.add(
                        RequestException.detail(
                                "body",
                                "$",
                                "UNEXPECTED_BODY",
                                "Эта операция не принимает тело запроса. Передайте данные в параметрах пути или строки запроса"));
            if (!errors.isEmpty())
                throw new RequestException(
                        400, "INVALID_REQUEST", validationMessage(errors), errors);
        } catch (RequestException e) {
            request.abortWith(ManagerExceptionMapper.response(e));
        }
    }

    private static String validationMessage(List<ErrorDetail> errors) {
        if (errors.size() != 1) return "Неверные параметры запроса";
        var error = errors.get(0);
        return switch (error.getCode()) {
            case "UNKNOWN_FIELD" -> "Неизвестный параметр запроса";
            case "INVALID_TYPE" -> "Неверный параметр " + error.getField();
            case "DUPLICATE_PARAMETER" -> "Повторяющийся параметр " + error.getField();
            case "REQUIRED" -> "Отсутствует обязательный параметр " + error.getField();
            case "UNEXPECTED_BODY" -> "Операция не принимает тело запроса";
            default -> "Неверные параметры запроса";
        };
    }

    private static void id(String value, String location, String field, List<ErrorDetail> errors) {
        try {
            if (!value.matches("[+]?[0-9]+")) throw new IllegalArgumentException();
            var n = new BigInteger(value);
            if (n.signum() <= 0 || n.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            errors.add(
                    RequestException.detail(
                            location,
                            field,
                            "INVALID_TYPE",
                            "ID должен быть целым числом от 1 до 9223372036854775807. Значение "
                                    + value
                                    + " не подходит"));
        }
    }

    static void accept(String header) {
        if (header == null) return;
        int specificity = -1;
        double quality = 0;
        try {
            for (String item : header.split(",", -1)) {
                var media = MediaType.valueOf(item);
                if (!media.isCompatible(MediaType.APPLICATION_XML_TYPE)) continue;
                int s = media.isWildcardType() ? 0 : media.isWildcardSubtype() ? 1 : 2;
                double q = Double.parseDouble(media.getParameters().getOrDefault("q", "1"));
                if (!Double.isFinite(q) || q < 0 || q > 1) throw new IllegalArgumentException();
                if (s > specificity) {
                    specificity = s;
                    quality = q;
                } else if (s == specificity) quality = Math.max(quality, q);
            }
        } catch (IllegalArgumentException e) {
            throw new RequestException(
                    400,
                    "INVALID_REQUEST",
                    "Неверный заголовок Accept",
                    List.of(
                            RequestException.detail(
                                    "header",
                                    "Accept",
                                    "INVALID_FORMAT",
                                    "Укажите корректный заголовок Accept")));
        }
        if (quality <= 0) throw RequestException.notAcceptable();
    }

    private static String allowedOrigin(ContainerRequestContext request) {
        var origin = request.getHeaderString("Origin");
        var allowed = System.getenv("CORS_ORIGINS");
        return origin != null
                        && allowed != null
                        && Arrays.asList(allowed.split(",")).contains(origin)
                ? origin
                : null;
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String origin = allowedOrigin(request);
        if (origin != null) {
            var h = response.getHeaders();
            h.putSingle("Access-Control-Allow-Origin", origin);
            h.add("Vary", "Origin");
            h.putSingle("Access-Control-Allow-Methods", "POST,OPTIONS");
            h.putSingle("Access-Control-Allow-Headers", "Accept,Content-Type");
            h.putSingle("Access-Control-Expose-Headers", "Retry-After");
        }
    }
}
