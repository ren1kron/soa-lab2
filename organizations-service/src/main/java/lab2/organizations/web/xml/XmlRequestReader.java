package lab2.organizations.web.xml;

import java.io.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.*;
import lab2.organizations.generated.model.ErrorDetail;
import lab2.organizations.web.error.RequestException;
import lab2.organizations.web.validation.ScalarValueRules;
import lab2.organizations.web.validation.ScalarValueRules.Kind;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

public final class XmlRequestReader {
    private XmlRequestReader() {}

    private record Rule(Kind kind, boolean required, Map<String, Rule> children) {}

    private static Rule scalar(Kind k, boolean required) {
        return new Rule(k, required, Map.of());
    }

    private static Rule object(boolean required, Object... entries) {
        var rules = new LinkedHashMap<String, Rule>();
        for (int i = 0; i < entries.length; i += 2)
            rules.put((String) entries[i], (Rule) entries[i + 1]);
        return new Rule(Kind.OBJECT, required, rules);
    }

    private static final Rule COORDINATES =
            object(true, "x", scalar(Kind.LONG, true), "y", scalar(Kind.INT, true));
    private static final Rule TOWN =
            object(
                    false,
                    "x",
                    scalar(Kind.LONG, true),
                    "y",
                    scalar(Kind.LONG, true),
                    "name",
                    scalar(Kind.TEXT, true));
    private static final Rule ADDRESS =
            object(false, "zipCode", scalar(Kind.ZIP, true), "town", TOWN);
    private static final Rule ORGANIZATION =
            object(
                    true,
                    "name",
                    scalar(Kind.NAME, true),
                    "coordinates",
                    COORDINATES,
                    "annualTurnover",
                    scalar(Kind.DOUBLE, false),
                    "type",
                    scalar(Kind.TYPE, false),
                    "officialAddress",
                    ADDRESS);
    private static final Rule EMPLOYEE =
            object(true, "name", scalar(Kind.NAME, true), "organizationId", scalar(Kind.ID, false));

    public static <T> T read(byte[] body, Class<T> type) {
        if (body.length == 0)
            throw new RequestException(
                    400,
                    "MISSING_BODY",
                    "An XML body is required",
                    List.of(
                            RequestException.detail(
                                    "body", "$", "REQUIRED", "An XML body is required")));
        Element root;
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            var b = f.newDocumentBuilder();
            b.setErrorHandler(new DefaultHandler());
            root = b.parse(new ByteArrayInputStream(body)).getDocumentElement();
        } catch (Exception e) {
            throw new RequestException(
                    400,
                    "MALFORMED_XML",
                    "Malformed or prohibited XML",
                    List.of(
                            RequestException.detail(
                                    "body",
                                    "$",
                                    "INVALID_FORMAT",
                                    "Expected well-formed XML without DTDs")));
        }
        boolean employee = type.getSimpleName().equals("EmployeeWrite");
        String expected = employee ? "employee" : "organization";
        var errors = new ArrayList<ErrorDetail>();
        if (!root.getTagName().equals(expected) || root.getNamespaceURI() != null)
            errors.add(
                    RequestException.detail(
                            "body", "$", "INVALID_FORMAT", "Expected root element " + expected));
        Map<String, Object> value = validate(root, employee ? EMPLOYEE : ORGANIZATION, "", errors);
        if (!errors.isEmpty())
            throw new RequestException(
                    422, "VALIDATION_FAILED", "XML content violates the contract", errors);
        return XmlModelMapper.JSON.convertValue(value, type);
    }

    private static Map<String, Object> validate(
            Element element, Rule rule, String path, List<ErrorDetail> errors) {
        var result = new LinkedHashMap<String, Object>();
        attributes(element, path, errors);
        var seen = new HashSet<String>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element child)) {
                if ((node.getNodeType() == Node.TEXT_NODE
                                || node.getNodeType() == Node.CDATA_SECTION_NODE)
                        && !node.getTextContent().isBlank())
                    errors.add(
                            RequestException.detail(
                                    "body",
                                    path.isEmpty() ? "$" : path,
                                    "INVALID_TYPE",
                                    "Objects contain elements, not text"));
                continue;
            }
            String name = child.getTagName(), field = path.isEmpty() ? name : path + "." + name;
            Rule childRule = rule.children.get(name);
            if (childRule == null || child.getNamespaceURI() != null) {
                errors.add(
                        RequestException.detail(
                                "body",
                                field,
                                path.isEmpty()
                                                && (Set.of("id", "creationDate").contains(name)
                                                        || (rule == ORGANIZATION
                                                                && name.equals("employeeCount")))
                                        ? "READ_ONLY_FIELD"
                                        : "UNKNOWN_FIELD",
                                "Element is not writable in this object"));
                continue;
            }
            if (!seen.add(name))
                errors.add(
                        RequestException.detail(
                                "body", field, "DUPLICATE_FIELD", "Singleton element is repeated"));
            if (childRule.kind == Kind.OBJECT)
                result.put(name, validate(child, childRule, field, errors));
            else {
                attributes(child, field, errors);
                boolean nested = false;
                for (Node n = child.getFirstChild(); n != null; n = n.getNextSibling())
                    if (n instanceof Element) nested = true;
                if (nested)
                    errors.add(
                            RequestException.detail(
                                    "body", field, "INVALID_TYPE", "Expected scalar text"));
                else
                    try {
                        result.put(
                                name,
                                ScalarValueRules.parse(childRule.kind, child.getTextContent()));
                    } catch (IllegalArgumentException e) {
                        errors.add(
                                RequestException.detail(
                                        "body",
                                        field,
                                        ScalarValueRules.errorCode(e),
                                        e.getMessage().split(":", 2)[1]));
                    }
            }
        }
        rule.children.forEach(
                (name, r) -> {
                    if (r.required && !seen.contains(name))
                        errors.add(
                                RequestException.detail(
                                        "body",
                                        path.isEmpty() ? name : path + "." + name,
                                        "REQUIRED",
                                        "Required element is missing"));
                });
        return result;
    }

    private static void attributes(Element e, String path, List<ErrorDetail> errors) {
        for (int i = 0; i < e.getAttributes().getLength(); i++) {
            var a = e.getAttributes().item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI()))
                errors.add(
                        RequestException.detail(
                                "body",
                                path.isEmpty() ? "$" : path,
                                "INVALID_FORMAT",
                                "Attributes, including xsi:nil, are unsupported"));
        }
    }
}
