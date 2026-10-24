package lab2.manager.infrastructure.organizations;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.*;
import lab2.manager.application.error.ServiceException;
import org.w3c.dom.*;
import org.xml.sax.helpers.DefaultHandler;

/** XML adapter around generated models; validates before Jackson can coerce scalars. */
final class UpstreamXmlReader {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    static Element document(byte[] bytes, String rootName) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!root.getTagName().equals(rootName) || root.getNamespaceURI() != null)
                throw ServiceException.upstream();
            return root;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw ServiceException.upstream();
        }
    }

    static <T> T read(byte[] bytes, String rootName, Class<T> type) {
        return read(document(bytes, rootName), type);
    }

    static <T> T read(Element element, Class<T> type) {
        try {
            return JSON.convertValue(bind(element, JSON.constructType(type)), type);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw ServiceException.upstream();
        }
    }

    private static Object bind(Element element, JavaType type) {
        for (int i = 0; i < element.getAttributes().getLength(); i++)
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(
                    element.getAttributes().item(i).getNamespaceURI()))
                throw ServiceException.upstream();
        Class<?> raw = type.getRawClass();
        if (type.isCollectionLikeType()) {
            var list = new ArrayList<Object>();
            for (var child : containerChildren(element)) {
                if (!child.getTagName().equals("detail")) throw ServiceException.upstream();
                list.add(bind(child, type.getContentType()));
            }
            return list;
        }
        if (raw.getName().startsWith("lab2.manager.")) {
            if (raw.isEnum()) {
                if (!children(element).isEmpty()) throw ServiceException.upstream();
                return JSON.convertValue(element.getTextContent(), raw);
            }
            var properties = JSON.getDeserializationConfig().introspect(type).findProperties();
            var byName =
                    new HashMap<
                            String,
                            com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition>();
            properties.forEach(p -> byName.put(p.getName(), p));
            var result = new LinkedHashMap<String, Object>();
            for (var child : containerChildren(element)) {
                var property = byName.get(child.getTagName());
                if (child.getNamespaceURI() != null
                        || property == null
                        || result.containsKey(child.getTagName()))
                    throw ServiceException.upstream();
                Object value = bind(child, property.getPrimaryType());
                validateProperty(raw.getSimpleName(), property.getName(), value);
                result.put(property.getName(), value);
            }
            for (var p : properties)
                if (p.getMetadata().isRequired() && !result.containsKey(p.getName()))
                    throw ServiceException.upstream();
            return result;
        }
        if (!children(element).isEmpty()) throw ServiceException.upstream();
        String text = element.getTextContent();
        try {
            if (raw == String.class) return text;
            if (raw == LocalDate.class) {
                if (!text.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                    throw ServiceException.upstream();
                return LocalDate.parse(text);
            }
            if (raw == Long.class || raw == Integer.class) {
                if (!text.matches("[+-]?[0-9]+")) throw ServiceException.upstream();
                var value = new BigInteger(text);
                return raw == Integer.class
                        ? (Object) value.intValueExact()
                        : value.longValueExact();
            }
            if (raw == Double.class) {
                if (!text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                    throw ServiceException.upstream();
                double value = Double.parseDouble(text);
                if (!Double.isFinite(value) || value <= 0) throw ServiceException.upstream();
                return value;
            }
        } catch (RuntimeException e) {
            throw ServiceException.upstream();
        }
        throw ServiceException.upstream();
    }

    private static void validateProperty(String model, String field, Object value) {
        if (Set.of("id", "organizationId", "acquiredId").contains(field)
                && (!(value instanceof Number n) || n.longValue() <= 0))
            throw ServiceException.upstream();
        if (Set.of("employeeCount", "count").contains(field)
                && (!(value instanceof Number n) || n.longValue() < 0))
            throw ServiceException.upstream();
        if (field.equals("name") && !model.equals("Location") && value.toString().isEmpty())
            throw ServiceException.upstream();
        if (field.equals("zipCode")
                && value.toString().codePointCount(0, value.toString().length()) > 18)
            throw ServiceException.upstream();
        if (Set.of("code", "message", "field").contains(field) && value.toString().isEmpty())
            throw ServiceException.upstream();
    }

    static List<Element> children(Element element) {
        var result = new ArrayList<Element>();
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) result.add(e);
            else if ((n.getNodeType() == Node.TEXT_NODE
                            || n.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !n.getTextContent().isBlank()) {
                // Scalar elements are validated by bind; mixed content is invalid for objects.
                boolean anyElement = false;
                for (Node child = element.getFirstChild();
                        child != null;
                        child = child.getNextSibling())
                    if (child instanceof Element) anyElement = true;
                if (anyElement) throw ServiceException.upstream();
            }
        }
        return result;
    }

    static List<Element> containerChildren(Element element) {
        for (int i = 0; i < element.getAttributes().getLength(); i++)
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(
                    element.getAttributes().item(i).getNamespaceURI()))
                throw ServiceException.upstream();
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling())
            if ((n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !n.getTextContent().isBlank()) throw ServiceException.upstream();
        return children(element);
    }
}
