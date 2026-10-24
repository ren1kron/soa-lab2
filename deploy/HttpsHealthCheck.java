import javax.net.ssl.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;

/** Dependency-free deployment probe; both application-server images provide Java. */
public final class HttpsHealthCheck {
    public static void main(String[] args) throws Exception {
        var store=KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null,null);
        try(var in=Files.newInputStream(Path.of(args[2]))) {
            store.setCertificateEntry("server",CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        var tls=SSLContext.getInstance("TLS"); tls.init(null,trust.getTrustManagers(),null);
        var client=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build();
        var request=HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(3)).header("Accept","application/xml")
            .method(args.length>3?args[3]:"GET",HttpRequest.BodyPublishers.noBody()).build();
        var response=client.send(request,HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()!=Integer.parseInt(args[1]) || !response.headers().firstValue("Content-Type").orElse("").startsWith("application/xml")) System.exit(1);
    }
}
