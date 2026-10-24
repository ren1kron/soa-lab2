package lab2.manager.infrastructure.organizations;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.SSLContext;
import lab2.manager.application.error.ServiceException;
import lab2.manager.application.port.OrganizationsGateway;
import lab2.manager.generated.upstream.model.*;

public final class HttpsOrganizationsClient implements OrganizationsGateway {
    private final URI base;
    private final HttpClient client;
    private final Duration timeout;

    public HttpsOrganizationsClient(
            URI base, SSLContext tls, Duration connectTimeout, Duration timeout) {
        if (!"https".equalsIgnoreCase(base.getScheme())
                || base.getHost() == null
                || base.getRawQuery() != null
                || base.getFragment() != null
                || base.getUserInfo() != null)
            throw new IllegalArgumentException("ORGANIZATIONS_URL must be an HTTPS base URL");
        this.base = URI.create(base.toString().replaceAll("/+$", ""));
        this.timeout = timeout;
        client =
                HttpClient.newBuilder()
                        .sslContext(tls)
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    private HttpResponse<byte[]> request(String method, String path, byte[] body, int expected) {
        var builder =
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(timeout)
                        .header("Accept", "application/xml");
        if (body != null) builder.header("Content-Type", "application/xml");
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 204 && expected == 204) {
                if (response.body().length != 0) throw ServiceException.upstream();
                return response;
            }
            String contentType =
                    response.headers()
                            .firstValue("Content-Type")
                            .orElse("")
                            .split(";", 2)[0]
                            .trim();
            if (!contentType.equalsIgnoreCase("application/xml")) throw ServiceException.upstream();
            if (status == expected) return response;
            throw UpstreamErrorTranslator.translate(status, response.body());
        } catch (HttpTimeoutException e) {
            throw new ServiceException(
                    "UPSTREAM_TIMEOUT",
                    "The first service timed out; the last change may have applied");
        } catch (IOException e) {
            throw new ServiceException(
                    "DEPENDENCY_UNAVAILABLE",
                    "Cannot reach the first service; check current state before repeating a change");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(
                    "SERVICE_UNAVAILABLE",
                    "Request interrupted; check current state before repeating a change");
        }
    }

    @Override
    public Organization organization(long id) {
        var value =
                UpstreamXmlReader.read(
                        request("GET", "/organizations/" + id, null, 200).body(),
                        "organization",
                        Organization.class);
        if (value.getId() != id) throw ServiceException.upstream();
        return value;
    }

    @Override
    public Employee employee(long id) {
        var value =
                UpstreamXmlReader.read(
                        request("GET", "/employees/" + id, null, 200).body(),
                        "employee",
                        Employee.class);
        if (value.getId() != id) throw ServiceException.upstream();
        return value;
    }

    @Override
    public Employee assign(Employee employee, long organizationId) {
        var input = new EmployeeWrite(employee.getName()).organizationId(organizationId);
        var value =
                UpstreamXmlReader.read(
                        request(
                                        "PUT",
                                        "/employees/" + employee.getId(),
                                        UpstreamXmlWriter.employee(input),
                                        200)
                                .body(),
                        "employee",
                        Employee.class);
        if (!Objects.equals(value.getId(), employee.getId())
                || !Objects.equals(value.getOrganizationId(), organizationId)
                || !Objects.equals(value.getName(), employee.getName()))
            throw ServiceException.upstream();
        return value;
    }

    private record Page(List<Employee> employees, long total) {}

    private Page page(long organizationId, long page, int size) {
        var response =
                request(
                        "GET",
                        "/employees?filter=organizationId:eq:"
                                + organizationId
                                + "&page="
                                + page
                                + "&size="
                                + size
                                + "&sort=id",
                        null,
                        200);
        long total = header(response, "X-Total-Count", 0);
        if (header(response, "X-Page", 1) != page || header(response, "X-Page-Size", 1) != size)
            throw ServiceException.upstream();
        var values = new ArrayList<Employee>();
        for (var child :
                UpstreamXmlReader.containerChildren(
                        UpstreamXmlReader.document(response.body(), "employees"))) {
            if (!child.getTagName().equals("employee")) throw ServiceException.upstream();
            var employee = UpstreamXmlReader.read(child, Employee.class);
            if (!Objects.equals(employee.getOrganizationId(), organizationId))
                throw ServiceException.upstream();
            values.add(employee);
        }
        if (values.size() > size || values.size() > total) throw ServiceException.upstream();
        long expected = Math.min(size, Math.max(0, total - (page - 1) * size));
        if (values.size() != expected) throw ServiceException.upstream();
        return new Page(values, total);
    }

    private static long header(HttpResponse<?> response, String name, long min) {
        try {
            var values = response.headers().allValues(name);
            if (values.size() != 1 || !values.get(0).matches("[0-9]+"))
                throw ServiceException.upstream();
            long n = Long.parseLong(values.get(0));
            if (n < min) throw ServiceException.upstream();
            return n;
        } catch (NumberFormatException e) {
            throw ServiceException.upstream();
        }
    }

    @Override
    public List<Long> employeeIds(long organizationId) {
        var ids = new LinkedHashSet<Long>();
        Long total = null;
        for (long number = 1; ; number++) {
            var p = page(organizationId, number, 100);
            if (total == null) total = p.total;
            else if (total != p.total)
                throw new ServiceException(
                        "CONCURRENT_MODIFICATION",
                        "The employee collection changed while taking the acquisition snapshot");
            for (var employee : p.employees)
                if (!ids.add(employee.getId())) throw ServiceException.upstream();
            if (ids.size() >= total) break;
            if (p.employees.isEmpty()) throw ServiceException.upstream();
        }
        return List.copyOf(ids);
    }

    @Override
    public long headcount(long organizationId) {
        return page(organizationId, 1, 1).total;
    }

    @Override
    public void deleteOrganization(long id) {
        request("DELETE", "/organizations/" + id, null, 204);
    }
}
