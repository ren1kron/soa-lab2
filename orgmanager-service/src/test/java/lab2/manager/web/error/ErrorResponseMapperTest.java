package lab2.manager.web.error;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import javax.xml.parsers.DocumentBuilderFactory;
import lab2.manager.application.error.ServiceException;
import lab2.manager.web.validation.ManagerRequestValidator;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class ErrorResponseMapperTest {
    @Test
    void preservesUpstreamFailureMeaningIndependentlyOfPublicCode() {
        var detail =
                new ServiceException.Detail(
                        "body", "organizationId", "MISSING", "Employer is missing");
        var error =
                new ServiceException(
                        ServiceException.Kind.NOT_FOUND, "CUSTOM_CODE", "Missing", List.of(detail));
        assertEquals(404, ErrorResponseMapper.status(error));
        var body = ErrorResponseMapper.body(error);
        assertEquals("CUSTOM_CODE", body.getCode());
        assertEquals("Missing", body.getMessage());
        assertEquals("body", body.getDetails().get(0).getLocation().toString());
        assertEquals("organizationId", body.getDetails().get(0).getField());
        assertEquals("MISSING", body.getDetails().get(0).getCode());
        assertEquals("Employer is missing", body.getDetails().get(0).getMessage());
    }

    @Test
    void mapsDependencyAndIntegrationFailuresToContractStatuses() {
        assertEquals(
                500,
                ErrorResponseMapper.status(
                        new ServiceException("INTEGRATION_FAILURE", "Rejected")));
        assertEquals(502, ErrorResponseMapper.status(ServiceException.upstream()));
        assertEquals(
                503,
                ErrorResponseMapper.status(
                        new ServiceException("DEPENDENCY_UNAVAILABLE", "Unavailable")));
        assertEquals(
                504,
                ErrorResponseMapper.status(
                        new ServiceException("UPSTREAM_TIMEOUT", "Unknown result")));
        assertEquals(
                409,
                ErrorResponseMapper.status(
                        new ServiceException("SELF_ACQUISITION", "Same organization")));
    }

    @Test
    void mapsAllServerFailuresToTheirExactPublicMessages() throws Exception {
        record Example(int status, String code, String message) {}
        for (var example :
                List.of(
                        new Example(
                                500,
                                "INTERNAL_ERROR",
                                "Не удалось завершить запрос. Если вы изменяли данные, проверьте, применились ли изменения"),
                        new Example(
                                500,
                                "INTEGRATION_FAILURE",
                                "Менеджер сформировал запрос, не соответствующий контракту первого сервиса"),
                        new Example(
                                500,
                                "UPSTREAM_INTERNAL_ERROR",
                                "В первом сервисе произошла ошибка. Если вы изменяли данные, проверьте, применились ли изменения"),
                        new Example(
                                502,
                                "INVALID_UPSTREAM_RESPONSE",
                                "Первый сервис вернул некорректный ответ. Проверьте состояние данных, прежде чем повторять изменение"),
                        new Example(
                                503,
                                "SERVICE_UNAVAILABLE",
                                "Сервис временно недоступен. Попробуйте получить данные позже"),
                        new Example(
                                503,
                                "DEPENDENCY_UNAVAILABLE",
                                "Не удалось связаться с первым сервисом. Проверьте состояние данных, прежде чем повторять изменение"),
                        new Example(
                                504,
                                "UPSTREAM_TIMEOUT",
                                "Первый сервис не ответил вовремя. Неизвестно, применилось ли последнее изменение"))) {
            try (var response =
                    ManagerExceptionMapper.response(
                            new ServiceException(example.code(), "private diagnostic"))) {
                assertEquals(example.status(), response.getStatus());
                var xml = xml(response);
                assertEquals(example.code(), text(xml, "code"));
                assertEquals(example.message(), text(xml, "message"));
                assertEquals(0, xml.getElementsByTagName("detail").getLength());
                assertNull(response.getHeaderString("Retry-After"));
            }
        }
        try (var response =
                new ManagerExceptionMapper()
                        .toResponse(new IllegalStateException("private diagnostic"))) {
            assertEquals(500, response.getStatus());
            assertEquals("INTERNAL_ERROR", text(xml(response), "code"));
            assertEquals(
                    "Не удалось завершить запрос. Если вы изменяли данные, проверьте, применились ли изменения",
                    text(xml(response), "message"));
        }
    }

    @Test
    void usesContractResponseForJaxRsNotAcceptableFallback() throws Exception {
        assertUnsupportedAccept(
                new ManagerExceptionMapper().toResponse(new NotAcceptableException()));
    }

    @Test
    void retainsAllDetailsWhenMultipleViolationsAreDetected() throws Exception {
        try (var response = validation("orgmanager/hire/abc", "unexpected", "1", true, null)) {
            assertEquals(400, response.getStatus());
            assertEquals(4, xml(response).getElementsByTagName("detail").getLength());
        }
    }

    @Test
    void rendersInvalidIdContainingACharacterForbiddenByXml() throws Exception {
        assertValidation(
                validation("orgmanager/hire/1", "employeeId", "1\0", false, null),
                400,
                "Неверный параметр employeeId",
                "query",
                "employeeId",
                "INVALID_TYPE",
                "ID должен быть целым числом от 1 до 9223372036854775807. Значение 1� не подходит");
        assertValidation(
                validation("orgmanager/hire/1\0", "employeeId", "1", false, null),
                400,
                "Неверный параметр id",
                "path",
                "id",
                "INVALID_TYPE",
                "ID должен быть целым числом от 1 до 9223372036854775807. Значение 1� не подходит");
    }

    @Test
    void rendersUnknownQueryNamesInXmlErrorDetails() throws Exception {
        assertValidation(
                validation("orgmanager/acquise/1/2", "bad\0name", "1", false, null),
                400,
                "Неизвестный параметр запроса",
                "query",
                "bad�name",
                "UNKNOWN_FIELD",
                "Эта операция не поддерживает параметр bad�name. Удалите его из запроса");
        assertValidation(
                validation("orgmanager/acquise/1/2", "", "1", false, null),
                400,
                "Неизвестный параметр запроса",
                "query",
                "$",
                "UNKNOWN_FIELD",
                "Эта операция не поддерживает параметр $. Удалите его из запроса");
    }

    private static void assertUnsupportedAccept(Response response) throws Exception {
        assertValidation(
                response,
                406,
                "Доступный формат ответа — application/xml",
                "header",
                "Accept",
                "UNSUPPORTED_MEDIA_TYPE",
                "Укажите application/xml или подходящую маску типа в Accept");
    }

    private static void assertValidation(
            Response response,
            int status,
            String message,
            String location,
            String field,
            String detailCode,
            String detailMessage)
            throws Exception {
        try (response) {
            assertEquals(status, response.getStatus());
            var xml = xml(response);
            assertEquals(status == 406 ? "NOT_ACCEPTABLE" : "INVALID_REQUEST", text(xml, "code"));
            assertEquals(message, text(xml, "message"));
            var details = xml.getElementsByTagName("detail");
            assertEquals(1, details.getLength());
            var detail = (Element) details.item(0);
            assertEquals(location, text(detail, "location"));
            assertEquals(field, text(detail, "field"));
            assertEquals(detailCode, text(detail, "code"));
            assertEquals(detailMessage, text(detail, "message"));
        }
    }

    private static Response validation(
            String path, String key, String value, boolean body, String accept) throws Exception {
        var query = new MultivaluedHashMap<String, String>();
        if (key != null) query.add(key, value);
        var uri =
                proxy(
                        UriInfo.class,
                        (method, args) ->
                                switch (method) {
                                    case "getPath" -> path;
                                    case "getQueryParameters" -> query;
                                    default -> throw new AssertionError(method);
                                });
        var response = new AtomicReference<Response>();
        var request =
                proxy(
                        ContainerRequestContext.class,
                        (method, args) ->
                                switch (method) {
                                    case "getMethod" -> "POST";
                                    case "getHeaderString" ->
                                            args[0].equals("Accept") ? accept : null;
                                    case "getUriInfo" -> uri;
                                    case "getEntityStream" ->
                                            new ByteArrayInputStream(
                                                    body ? new byte[] {1} : new byte[0]);
                                    case "abortWith" -> {
                                        response.set((Response) args[0]);
                                        yield null;
                                    }
                                    default -> throw new AssertionError(method);
                                });
        new ManagerRequestValidator().filter(request);
        return response.get();
    }

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (instance, method, args) -> handler.apply(method.getName(), args)));
    }

    private static Element xml(Response response) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream((byte[]) response.getEntity()))
                .getDocumentElement();
    }

    private static String text(Element element, String name) {
        return element.getElementsByTagName(name).item(0).getTextContent();
    }
}
