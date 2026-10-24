package lab2.manager.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.net.URI;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.*;
import lab2.manager.application.port.OrganizationsGateway;
import lab2.manager.infrastructure.organizations.HttpsOrganizationsClient;

@ApplicationScoped
public class UpstreamClientConfiguration {
    @Produces
    @ApplicationScoped
    public OrganizationsGateway organizations() {
        try {
            var store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(Path.of(required("UPSTREAM_TRUSTSTORE")))) {
                store.load(input, required("UPSTREAM_TRUSTSTORE_PASSWORD").toCharArray());
            }
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, trust.getTrustManagers(), null);
            return new HttpsOrganizationsClient(
                    URI.create(required("ORGANIZATIONS_URL")),
                    tls,
                    seconds("UPSTREAM_CONNECT_TIMEOUT_SECONDS", 5),
                    seconds("UPSTREAM_TIMEOUT_SECONDS", 15));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot configure organizations client", error);
        }
    }

    private static Duration seconds(String key, int fallback) {
        return Duration.ofSeconds(
                Long.parseLong(System.getenv().getOrDefault(key, Integer.toString(fallback))));
    }

    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isEmpty())
            throw new IllegalStateException("Missing configuration: " + key);
        return value;
    }
}
