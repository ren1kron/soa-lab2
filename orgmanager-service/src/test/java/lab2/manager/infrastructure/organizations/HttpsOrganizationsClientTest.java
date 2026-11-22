package lab2.manager.infrastructure.organizations;

import static lab2.manager.application.error.ServiceException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import lab2.manager.application.error.ServiceException;
import lab2.manager.web.error.ManagerExceptionMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HttpsOrganizationsClientTest {
    @TempDir static Path temp;
    static SSLContext tls;
    private HttpsServer server;
    private HttpsOrganizationsClient client;
    private int status = 200;
    private String body =
            "<organization><id>1</id><name>ok</name><coordinates><x>1</x><y>2</y></coordinates><creationDate>2026-10-24</creationDate><employeeCount>0</employeeCount></organization>";
    private String contentType = "application/xml";
    private final AtomicInteger calls = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<String> requestBody =
            new java.util.concurrent.atomic.AtomicReference<>();
    private long delay;
    private java.util.function.Function<HttpExchange, String> dynamic;
    private final java.util.Map<String, String> headers = new java.util.HashMap<>();
    private java.util.List<String> retryAfter = java.util.List.of();

    @BeforeAll
    static void certificates() throws Exception {
        Path store = temp.resolve("test.p12");
        var p =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString(),
                                "-genkeypair",
                                "-alias",
                                "test",
                                "-keyalg",
                                "RSA",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                store.toString(),
                                "-storepass",
                                "password",
                                "-dname",
                                "CN=localhost",
                                "-ext",
                                "SAN=dns:localhost,ip:127.0.0.1",
                                "-validity",
                                "1",
                                "-noprompt")
                        .redirectErrorStream(true)
                        .start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        var ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            ks.load(in, "password".toCharArray());
        }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(ks, "password".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(ks);
        tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                    if (delay > 0)
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    String responseBody = dynamic == null ? body : dynamic.apply(exchange);
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    headers.forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
                    retryAfter.forEach(
                            value -> exchange.getResponseHeaders().add("Retry-After", value));
                    if (status == 204) exchange.sendResponseHeaders(status, -1);
                    else {
                        byte[] bytes =
                                responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                    exchange.close();
                });
        server.start();
        client =
                new HttpsOrganizationsClient(
                        URI.create("https://localhost:" + server.getAddress().getPort()),
                        tls,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsValidatedXmlOverTrustedHttps() {
        assertEquals(1L, client.organization(1).getId());
        assertEquals(0L, client.organization(1).getEmployeeCount());
        body =
                body.replace(
                        "<employeeCount>0</employeeCount>",
                        "<employeeCount>9223372036854775807</employeeCount>");
        assertEquals(Long.MAX_VALUE, client.organization(1).getEmployeeCount());
    }

    @Test
    void rejectsMissingNegativeAndMalformedEmployeeCounts() {
        String valid = body;
        for (String count :
                new String[] {
                    "",
                    "<employeeCount>-1</employeeCount>",
                    "<employeeCount>1.5</employeeCount>",
                    "<employeeCount>9223372036854775808</employeeCount>"
                }) {
            body = valid.replace("<employeeCount>0</employeeCount>", count);
            assertEquals(
                    INVALID_UPSTREAM_RESPONSE,
                    assertThrows(ServiceException.class, () -> client.organization(1)).kind);
        }
    }

    @Test
    void sendsEscapedEmployeeAssignmentWithFullWidthEmployerId() {
        body =
                "<employee><id>10</id><name> A &amp; B &lt;team&gt;&#13;\nend&#13; </name><organizationId>"
                        + Long.MAX_VALUE
                        + "</organizationId></employee>";
        var employee =
                new lab2.manager.generated.upstream.model.Employee(10L, " A & B <team>\r\nend\r ");
        assertEquals(Long.MAX_VALUE, client.assign(employee, Long.MAX_VALUE).getOrganizationId());
        var request =
                UpstreamXmlReader.document(
                        requestBody.get().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "employee");
        assertEquals(
                employee.getName(), request.getElementsByTagName("name").item(0).getTextContent());
        assertEquals(
                Long.toString(Long.MAX_VALUE),
                request.getElementsByTagName("organizationId").item(0).getTextContent());
        assertEquals(0, request.getElementsByTagName("id").getLength());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsWrongRootMissingFieldsAndInvalidContentType() {
        body = "<wrong/>";
        assertEquals(
                INVALID_UPSTREAM_RESPONSE,
                assertThrows(ServiceException.class, () -> client.organization(1)).kind);
        body = "<organization><id>1</id></organization>";
        assertEquals(
                INVALID_UPSTREAM_RESPONSE,
                assertThrows(ServiceException.class, () -> client.organization(1)).kind);
        contentType = "application/json";
        assertEquals(
                INVALID_UPSTREAM_RESPONSE,
                assertThrows(ServiceException.class, () -> client.organization(1)).kind);
    }

    @Test
    void rejectsResponseForAnotherResource() {
        body = body.replace("<id>1</id>", "<id>2</id>");
        assertEquals(
                INVALID_UPSTREAM_RESPONSE,
                assertThrows(ServiceException.class, () -> client.organization(1)).kind);
    }

    @Test
    void translatesErrorStatusesAndDoesNotRetryMutations() {
        body = "<error><code>VALIDATION_FAILED</code><message>bad</message><details/></error>";
        for (int rejected : new int[] {400, 415, 422}) {
            status = rejected;
            assertEquals(
                    "INTEGRATION_FAILURE",
                    assertThrows(ServiceException.class, () -> client.organization(1)).code);
        }
        status = 500;
        assertEquals(
                "UPSTREAM_INTERNAL_ERROR",
                assertThrows(ServiceException.class, () -> client.deleteOrganization(1)).code);
        assertEquals(4, calls.get());
        status = 409;
        assertEquals(
                CONFLICT,
                assertThrows(ServiceException.class, () -> client.deleteOrganization(1)).kind);
    }

    @Test
    void preservesValidUpstreamNotFoundAndConflictThroughManagerXml() {
        body =
                "<error><code>CUSTOM</code><message> upstream&#13;message </message>"
                        + "<details><detail><location>body</location><field>name</field><code>CUSTOM_DETAIL</code>"
                        + "<message> upstream&#13;detail </message></detail></details></error>";
        for (int upstreamStatus : new int[] {404, 409}) {
            status = upstreamStatus;
            var error = assertThrows(ServiceException.class, () -> client.deleteOrganization(1));
            try (var response = ManagerExceptionMapper.response(error)) {
                assertEquals(upstreamStatus, response.getStatus());
                var xml =
                        UpstreamXmlReader.read(
                                (byte[]) response.getEntity(),
                                "error",
                                lab2.manager.generated.model.Error.class);
                assertEquals("CUSTOM", xml.getCode());
                assertEquals(" upstream\rmessage ", xml.getMessage());
                var detail = xml.getDetails().get(0);
                assertEquals("body", detail.getLocation().toString());
                assertEquals("name", detail.getField());
                assertEquals("CUSTOM_DETAIL", detail.getCode());
                assertEquals(" upstream\rdetail ", detail.getMessage());
            }
        }
    }

    @Test
    void forwardsOnlyOneDecimalRetryAfterOnValidUpstream503WithoutRepeatingDelete() {
        status = 503;
        body = "<error><code>PRIVATE</code><message>private message</message><details/></error>";
        for (var values :
                java.util.List.of(
                        java.util.List.of("30"),
                        java.util.List.of("0"),
                        java.util.List.of("0005"),
                        java.util.List.of("9223372036854775808"))) {
            retryAfter = values;
            assertEquals(values.get(0), managerRetryAfter(503));
        }
        for (var values :
                java.util.List.<java.util.List<String>>of(
                        java.util.List.of(),
                        java.util.List.of("-1"),
                        java.util.List.of("+1"),
                        java.util.List.of("1.5"),
                        java.util.List.of("5, 5"),
                        java.util.List.of("5", "5"),
                        java.util.List.of("Wed, 21 Oct 2015 07:28:00 GMT"))) {
            retryAfter = values;
            assertNull(managerRetryAfter(503));
        }
        retryAfter = java.util.List.of("30");
        status = 504;
        assertNull(managerRetryAfter(504));
        status = 503;
        body = "<wrong/>";
        assertNull(managerRetryAfter(502));
    }

    private String managerRetryAfter(int expectedStatus) {
        int previousCalls = calls.get();
        var error = assertThrows(ServiceException.class, () -> client.deleteOrganization(1));
        assertEquals(previousCalls + 1, calls.get());
        try (var response = ManagerExceptionMapper.response(error)) {
            assertEquals(expectedStatus, response.getStatus());
            return response.getHeaderString("Retry-After");
        }
    }

    @Test
    void rejectsMissingPaginationHeaders() {
        body = "<employees/>";
        assertEquals(
                INVALID_UPSTREAM_RESPONSE,
                assertThrows(ServiceException.class, () -> client.headcount(1)).kind);
    }

    @Test
    void forbidsUnencryptedUpstreamConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new HttpsOrganizationsClient(
                                URI.create("http://localhost:8080"),
                                tls,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(1)));
    }

    @Test
    void collectsEveryEmployeePageBeforeReturningSnapshot() {
        dynamic =
                exchange -> {
                    int page = exchange.getRequestURI().getRawQuery().contains("page=2") ? 2 : 1;
                    headers.put("X-Total-Count", "105");
                    headers.put("X-Page", Integer.toString(page));
                    headers.put("X-Page-Size", "100");
                    var xml = new StringBuilder("<employees>");
                    for (int i = page == 1 ? 1 : 101; i <= (page == 1 ? 100 : 105); i++)
                        xml.append("<employee><id>")
                                .append(i)
                                .append(
                                        "</id><name>x</name><organizationId>1</organizationId></employee>");
                    return xml.append("</employees>").toString();
                };
        var ids = client.employeeIds(1);
        assertEquals(105, ids.size());
        assertEquals(105L, ids.get(104));
        assertEquals(2, calls.get());
    }

    @Test
    void mapsTimeoutAndDoesNotRepeatMutation() {
        delay = 900;
        client =
                new HttpsOrganizationsClient(
                        URI.create("https://localhost:" + server.getAddress().getPort()),
                        tls,
                        Duration.ofSeconds(2),
                        Duration.ofMillis(300));
        assertEquals(
                UPSTREAM_TIMEOUT,
                assertThrows(ServiceException.class, () -> client.deleteOrganization(1)).kind);
        assertTrue(calls.get() <= 1);
    }

    @Test
    void rejectsTextInsideAnEmptyCollectionOrErrorDetailsWrapper() {
        headers.put("X-Total-Count", "0");
        headers.put("X-Page", "1");
        headers.put("X-Page-Size", "1");
        body = "<employees>garbage</employees>";
        assertEquals(
                INVALID_UPSTREAM_RESPONSE,
                assertThrows(ServiceException.class, () -> client.headcount(1)).kind);
        status = 404;
        body =
                "<error><code>NOT_FOUND</code><message>missing</message><details>garbage</details></error>";
        assertEquals(
                INVALID_UPSTREAM_RESPONSE,
                assertThrows(ServiceException.class, () -> client.organization(1)).kind);
        status = 200;
        body = "<employees xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:nil='true'/>";
        assertEquals(
                INVALID_UPSTREAM_RESPONSE,
                assertThrows(ServiceException.class, () -> client.headcount(1)).kind);
    }
}
