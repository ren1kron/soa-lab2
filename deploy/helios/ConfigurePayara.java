import java.nio.file.*;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.*;

/** Applies native Helios settings offline, before the first server JVM starts. */
public class ConfigurePayara {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: ConfigurePayara.java domain.xml httpsPort adminPort");
        int https = port(args[1]), admin = port(args[2]);
        if (https == admin) throw new IllegalArgumentException("HTTPS and admin ports must differ");
        Path file = Path.of(args[0]);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document doc = factory.newDocumentBuilder().parse(file.toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        String config = "/domain/configs/config[@name='server-config']";
        Element java = (Element) xpath.evaluate(config + "/java-config", doc, javax.xml.xpath.XPathConstants.NODE);
        if (java == null) throw new IllegalArgumentException("Expected Payara server-config/java-config");
        for (Node child = java.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if (child.getNodeName().equals("jvm-options")) {
                String value = child.getTextContent().strip();
                if (managedOption(value)) java.removeChild(child);
                String bundles = "-Dorg.glassfish.additionalOSGiBundlesToStart=";
                if (value.startsWith(bundles))
                    child.setTextContent(bundles + String.join(",", List.of(value.substring(bundles.length()).split(",")).stream()
                            .filter(bundle -> !bundle.equals("org.apache.felix.shell.remote")).toList()));
            }
            child = next;
        }
        for (String option : List.of("-Xms64m", "-Xmx256m", "-Xss512k", "-XX:MaxMetaspaceSize=192m",
                "-XX:ReservedCodeCacheSize=64m", "-XX:MaxDirectMemorySize=32m", "-XX:+UseSerialGC",
                "-XX:ActiveProcessorCount=2")) {
            var node = doc.createElement("jvm-options");
            node.setTextContent(option);
            java.appendChild(node);
        }
        set(doc, "/domain/hazelcast-runtime-configuration", "enabled", "false");
        String listeners = config + "/network-config/network-listeners/network-listener";
        set(doc, listeners + "[@name='http-listener-1']", "enabled", "false");
        set(doc, listeners + "[@name='http-listener-2']", "enabled", "true");
        set(doc, listeners + "[@name='http-listener-2']", "port", Integer.toString(https));
        set(doc, listeners + "[@name='http-listener-2']", "address", "127.0.0.1");
        set(doc, listeners + "[@name='admin-listener']", "port", Integer.toString(admin));
        set(doc, listeners + "[@name='admin-listener']", "address", "127.0.0.1");
        String protocol = config + "/network-config/protocols/protocol[@name='http-listener-2']";
        set(doc, protocol, "security-enabled", "true");
        set(doc, protocol + "/ssl", "cert-nickname", "server");
        set(doc, protocol + "/http", "allow-payload-for-undefined-http-methods", "true");
        set(doc, config + "/iiop-service/iiop-listener", "enabled", "false");
        set(doc, config + "/admin-service/jmx-connector", "enabled", "false");
        // Some domain templates omit JMS entirely; do not introduce a broker there.
        if (xpath.evaluate(config + "/jms-service", doc, javax.xml.xpath.XPathConstants.NODE) != null)
            set(doc, config + "/jms-service", "type", "REMOTE");
        set(doc, config + "/thread-pools/thread-pool", "max-thread-pool-size", "16");
        set(doc, config + "/thread-pools/thread-pool", "min-thread-pool-size", "2");
        Path backup = file.resolveSibling(file.getFileName() + ".before-helios");
        if (!Files.exists(backup)) Files.copy(file, backup);
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "domain-", ".xml");
        try {
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.transform(new DOMSource(doc), new StreamResult(temporary.toFile()));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        System.out.println("Configured Payara: HTTPS " + https + ", loopback admin " + admin + ", heap 256 MiB");
    }

    private static int port(String value) {
        int port = Integer.parseInt(value);
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Use an unprivileged port from 1024 to 65535");
        return port;
    }

    private static boolean managedOption(String value) {
        // Preserve Payara's module opens/exports and all unrelated server properties.
        return value.matches("(?:\\[[^]]*])?-(?:Xms|Xmx|Xss).*|(?:\\[[^]]*])?-XX:(?:MaxHeapSize|InitialHeapSize|MaxMetaspaceSize|MetaspaceSize|ReservedCodeCacheSize|MaxDirectMemorySize|ActiveProcessorCount|MaxRAMPercentage|InitialRAMPercentage)=.*|(?:\\[[^]]*])?-XX:[+-]Use(?:G1|Parallel|ParallelOld|ConcMarkSweep|Serial|Z|Shenandoah)GC");
    }

    private static void set(Document doc, String path, String attribute, String value) throws Exception {
        var nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(path, doc, javax.xml.xpath.XPathConstants.NODESET);
        if (nodes.getLength() == 0) throw new IllegalArgumentException("Missing expected Payara configuration: " + path);
        for (int i = 0; i < nodes.getLength(); i++) ((Element) nodes.item(i)).setAttribute(attribute, value);
    }
}
