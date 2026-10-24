package lab2.organizations.web.validation;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import lab2.organizations.generated.model.ErrorDetail;
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
            requireXmlAccept(original.getHeader("Accept"));
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
            var errors = new ArrayList<ErrorDetail>();
            if (list)
                request.setAttribute(
                        "collectionQuery",
                        CollectionQueryParser.parse(request.getParameterMap(), employee));
            else {
                Set<String> allowed =
                        path.equals("/organizations/count") && request.getMethod().equals("GET")
                                ? Set.of("typeGreaterThan")
                                : Set.of();
                request.getParameterMap()
                        .forEach(
                                (key, v) -> {
                                    if (!allowed.contains(key))
                                        errors.add(
                                                RequestException.detail(
                                                        "query",
                                                        key,
                                                        "UNKNOWN_FIELD",
                                                        "Unknown query parameter"));
                                    if (v.length != 1)
                                        errors.add(
                                                RequestException.detail(
                                                        "query",
                                                        key,
                                                        "DUPLICATE_PARAMETER",
                                                        "Pass this parameter once"));
                                });
                if (allowed.contains("typeGreaterThan")) {
                    String value = request.getParameter("typeGreaterThan");
                    if (value == null)
                        errors.add(
                                RequestException.detail(
                                        "query",
                                        "typeGreaterThan",
                                        "REQUIRED",
                                        "This query parameter is required"));
                    else if (!ScalarValueRules.TYPES.contains(value))
                        errors.add(
                                RequestException.detail(
                                        "query",
                                        "typeGreaterThan",
                                        "INVALID_ENUM",
                                        "Unknown organization type"));
                }
            }
            String[] segments = path.split("/");
            if (segments.length == 3
                    && (segments[1].equals("employees")
                            || segments[1].equals("organizations")
                                    && !segments[2].equals("count"))) {
                try {
                    ScalarValueRules.parse(ScalarValueRules.Kind.ID, segments[2]);
                } catch (IllegalArgumentException e) {
                    errors.add(
                            RequestException.detail(
                                    "path",
                                    employee ? "employeeId" : "id",
                                    "INVALID_TYPE",
                                    "ID must be an integer from 1 to 9223372036854775807"));
                }
            }
            if (segments.length == 4
                    && path.startsWith("/organizations/type/")
                    && !ScalarValueRules.TYPES.contains(segments[3]))
                errors.add(
                        RequestException.detail(
                                "path", "type", "INVALID_ENUM", "Unknown organization type"));
            if (!write && request.body.length > 0)
                errors.add(
                        RequestException.detail(
                                "body",
                                "$",
                                "UNEXPECTED_BODY",
                                "This operation does not accept a body"));
            if (!errors.isEmpty())
                throw new RequestException(400, "INVALID_REQUEST", "Invalid request", errors);
            if (write) {
                if (request.body.length == 0)
                    throw new RequestException(
                            400,
                            "MISSING_BODY",
                            "An XML body is required",
                            List.of(
                                    RequestException.detail(
                                            "body", "$", "REQUIRED", "An XML body is required")));
                String encoding = request.getHeader("Content-Encoding");
                if (encoding != null && !encoding.equalsIgnoreCase("identity"))
                    throw media("Content-Encoding");
                try {
                    var contentType =
                            MediaType.parseMediaType(
                                    Objects.requireNonNullElse(request.getContentType(), ""));
                    if (!contentType.getType().equalsIgnoreCase("application")
                            || !contentType.getSubtype().equalsIgnoreCase("xml"))
                        throw media("Content-Type");
                } catch (IllegalArgumentException e) {
                    throw media("Content-Type");
                }
            }
            chain.doFilter(request, response);
        } catch (RequestException e) {
            response.setStatus(e.status);
            response.setContentType("application/xml;charset=UTF-8");
            response.getOutputStream().write(XmlResponseWriter.write(e.body(), "error"));
        }
    }

    private static RequestException media(String field) {
        return new RequestException(
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "Use application/xml with no content encoding",
                List.of(
                        RequestException.detail(
                                "header",
                                field,
                                "UNSUPPORTED_MEDIA_TYPE",
                                "Use application/xml and identity encoding")));
    }

    static void requireXmlAccept(String header) {
        if (header == null) return;
        int specificity = -1;
        double quality = 0;
        try {
            for (var type : MediaType.parseMediaTypes(header)) {
                if (!type.includes(MediaType.APPLICATION_XML)) continue;
                int s = type.isWildcardType() ? 0 : type.isWildcardSubtype() ? 1 : 2;
                if (s > specificity) {
                    specificity = s;
                    quality = type.getQualityValue();
                } else if (s == specificity) quality = Math.max(quality, type.getQualityValue());
            }
        } catch (IllegalArgumentException e) {
            throw RequestException.invalid(
                    "header", "Accept", "INVALID_FORMAT", "Invalid Accept header");
        }
        if (quality <= 0)
            throw new RequestException(
                    406,
                    "NOT_ACCEPTABLE",
                    "The available response format is application/xml",
                    List.of(
                            RequestException.detail(
                                    "header",
                                    "Accept",
                                    "UNSUPPORTED_MEDIA_TYPE",
                                    "Allow application/xml")));
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
