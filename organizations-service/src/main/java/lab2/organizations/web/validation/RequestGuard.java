package lab2.organizations.web.validation;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import lab2.organizations.web.error.RequestException;
import lab2.organizations.web.xml.XmlResponseWriter;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RequestGuard extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest original, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        cors(original, response);
        if (original.getMethod().equals("OPTIONS")
                && response.getHeader("Access-Control-Allow-Origin") != null) {
            response.setStatus(204);
            return;
        }
        try {
            requireXmlAccept(Collections.list(original.getHeaders("Accept")));
            var request = new CachedRequest(original);
            String path =
                    java.net
                            .URI
                            .create(request.getRequestURI())
                            .getPath()
                            .substring(request.getContextPath().length());
            boolean employee = path.startsWith("/employees");
            boolean list =
                    (path.equals("/organizations") || path.equals("/employees"))
                            && request.getMethod().equals("GET");
            boolean write =
                    (path.equals("/organizations") || path.equals("/employees"))
                                    && request.getMethod().equals("POST")
                            || (path.matches("/(organizations|employees)/[^/]+")
                                    && request.getMethod().equals("PUT"));
            var errors = new RequestErrors();
            if (list) {
                try {
                    request.setAttribute(
                            "collectionQuery",
                            CollectionQueryParser.parse(request.getParameterMap(), employee));
                } catch (RequestException e) {
                    errors.add(e);
                }
            } else {
                boolean count =
                        path.equals("/organizations/count") && request.getMethod().equals("GET");
                errors.checkParameters(
                        request.getParameterMap(), count ? Set.of("typeGreaterThan") : Set.of());
                if (count) {
                    String value = request.getParameter("typeGreaterThan");
                    if (value == null)
                        errors.invalid(
                                "Отсутствует обязательный параметр typeGreaterThan",
                                "query",
                                "typeGreaterThan",
                                "REQUIRED",
                                "Передайте обязательный query-параметр typeGreaterThan");
                    else if (!ScalarValueRules.TYPES.contains(value))
                        invalidType(errors, "query", "typeGreaterThan");
                }
            }
            String[] segments = path.split("/");
            if (segments.length == 3
                    && (segments[1].equals("employees")
                            || segments[1].equals("organizations")
                                    && !segments[2].equals("count"))) {
                String value = segments[2];
                try {
                    ScalarValueRules.parse(ScalarValueRules.Kind.ID, value);
                } catch (IllegalArgumentException e) {
                    String field = employee ? "employeeId" : "id";
                    errors.invalid(
                            "Неверный параметр " + field,
                            "path",
                            field,
                            "INVALID_TYPE",
                            "ID должен быть целым числом от 1 до 9223372036854775807. Значение "
                                    + value
                                    + " не подходит");
                }
            }
            if (segments.length == 4
                    && path.startsWith("/organizations/type/")
                    && !ScalarValueRules.TYPES.contains(segments[3]))
                invalidType(errors, "path", "type");
            if (!write && request.body.length > 0)
                errors.invalid(
                        "Операция не принимает тело запроса",
                        "body",
                        "$",
                        "UNEXPECTED_BODY",
                        "Эта операция не принимает тело запроса. Передайте данные в параметрах пути или строки запроса");
            if (write && request.body.length == 0)
                errors.add(RequestException.missingBody(employee ? "employee" : "organization"));
            errors.requireValid();
            if (write) requireXmlContent(request);
            chain.doFilter(request, response);
        } catch (RequestException e) {
            response.setStatus(e.status);
            response.setContentType("application/xml;charset=UTF-8");
            response.getOutputStream().write(XmlResponseWriter.write(e.body(), "error"));
        }
    }

    private static void invalidType(RequestErrors errors, String location, String field) {
        errors.invalid(
                "Неверный параметр " + field,
                location,
                field,
                "INVALID_ENUM",
                "Допустимы только PUBLIC, GOVERNMENT, TRUST, PRIVATE_LIMITED_COMPANY");
    }

    private static void requireXmlContent(HttpServletRequest request) {
        if (Collections.list(request.getHeaders("Content-Encoding")).stream()
                .anyMatch(value -> !value.equalsIgnoreCase("identity")))
            throw new RequestException(
                    415,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "Кодирование тела запроса не поддерживается",
                    List.of(
                            RequestException.detail(
                                    "header",
                                    "Content-Encoding",
                                    "UNSUPPORTED_ENCODING",
                                    "Используйте identity или не передавайте Content-Encoding")));
        String value = request.getContentType();
        if (value != null) {
            try {
                var type = MediaType.parseMediaType(value);
                if (type.getType().equalsIgnoreCase("application")
                        && type.getSubtype().equalsIgnoreCase("xml")) return;
                value = type.getType() + "/" + type.getSubtype();
            } catch (IllegalArgumentException e) {
                // Use the same response for malformed and unsupported types.
            }
        }
        String hint = "Отправьте XML с заголовком Content-Type: application/xml.";
        if (value != null) hint += " Формат " + value + " не поддерживается";
        throw new RequestException(
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "Тип тела запроса не поддерживается",
                List.of(
                        RequestException.detail(
                                "header", "Content-Type", "UNSUPPORTED_MEDIA_TYPE", hint)));
    }

    private static void requireXmlAccept(List<String> headers) {
        if (headers.isEmpty()) return;
        int specificity = -1;
        double quality = 0;
        try {
            for (var type : MediaType.parseMediaTypes(headers)) {
                if (!type.includes(MediaType.APPLICATION_XML)) continue;
                int s = type.isWildcardType() ? 0 : type.isWildcardSubtype() ? 1 : 2;
                if (s > specificity) {
                    specificity = s;
                    quality = type.getQualityValue();
                } else if (s == specificity) quality = Math.max(quality, type.getQualityValue());
            }
        } catch (IllegalArgumentException e) {
            throw RequestException.invalid(
                    "header", "Accept", "INVALID_FORMAT", "Заголовок Accept имеет неверный формат");
        }
        if (quality <= 0)
            throw new RequestException(
                    406,
                    "NOT_ACCEPTABLE",
                    "Доступный формат ответа – application/xml",
                    List.of(
                            RequestException.detail(
                                    "header",
                                    "Accept",
                                    "UNSUPPORTED_MEDIA_TYPE",
                                    "Укажите application/xml или подходящую маску типа в Accept")));
    }

    private static void cors(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin"), configured = System.getenv("CORS_ORIGINS");
        if (origin != null
                && configured != null
                && Arrays.asList(configured.split(",")).contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type,Accept");
            response.setHeader(
                    "Access-Control-Expose-Headers",
                    "Location,X-Total-Count,X-Page,X-Page-Size,Retry-After");
        }
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedRequest(HttpServletRequest original) throws IOException {
            super(original);
            body = original.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            var in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous request body");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(
                            getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
