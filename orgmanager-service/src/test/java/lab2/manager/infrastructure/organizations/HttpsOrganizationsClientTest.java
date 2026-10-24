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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HttpsOrganizationsClientTest {
    @TempDir static Path temp;
    static SSLContext tls;
    private HttpsServer server;
    private HttpsOrganizationsClient client;
    private int status = 200;
    private String body =
            "<organization><id>1</id><name>ok</name><coordinates><x>1</x><y>2</y></coordinates><creationDate>2026-10-24</creationDate></organization>";
    private String contentType = "application/xml";
    private final AtomicInteger calls = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<String> requestBody =
            new java.util.concurrent.atomic.AtomicReference<>();
    private long delay;
    private java.util.function.Function<HttpExchange, String> dynamic;
    private final java.util.Map<String, String> headers = new java.util.HashMap<>();

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
    }

    @Test
    void sendsEscapedEmployeeAssignmentWithFullWidthEmployerId() {
        body =
                "<employee><id>10</id><name> A &amp; B &lt;team&gt; </name><organizationId>"
                        + Long.MAX_VALUE
                        + "</organizationId></employee>";
        var employee = new lab2.manager.generated.upstream.model.Employee(10L, " A & B <team> ");
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
        status = 422;
        assertEquals(
                "INTEGRATION_FAILURE",
                assertThrows(ServiceException.class, () -> client.organization(1)).code);
        status = 500;
        assertEquals(
                "UPSTREAM_INTERNAL_ERROR",
                assertThrows(ServiceException.class, () -> client.deleteOrganization(1)).code);
        assertEquals(2, calls.get());
        status = 409;
        assertEquals(
                CONFLICT,
                assertThrows(ServiceException.class, () -> client.deleteOrganization(1)).kind);
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
