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
        boolean employee = type.getSimpleName().equals("EmployeeWrite");
        String expected = employee ? "employee" : "organization";
        if (body.length == 0) throw RequestException.missingBody(expected);
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
                    "XML-тело не удалось разобрать",
                    List.of(
                            RequestException.detail(
                                    "body",
                                    "$",
                                    "INVALID_FORMAT",
                                    "XML должен содержать один корректно закрытый корневой элемент")));
        }
        if (!root.getTagName().equals(expected) || root.getNamespaceURI() != null)
            throw new RequestException(
                    422,
                    "VALIDATION_FAILED",
                    "Неверный корневой элемент",
                    List.of(
                            RequestException.detail(
                                    "body",
                                    "$",
                                    "INVALID_FORMAT",
                                    "Корневой элемент XML должен называться " + expected)));
        var errors = new ArrayList<ErrorDetail>();
        Map<String, Object> value = validate(root, employee ? EMPLOYEE : ORGANIZATION, "", errors);
        if (!errors.isEmpty())
            throw new RequestException(
                    422, "VALIDATION_FAILED", validationMessage(errors, employee), errors);
        return XmlModelMapper.JSON.convertValue(value, type);
    }

    private static String validationMessage(List<ErrorDetail> errors, boolean employee) {
        if (errors.size() > 1)
            return employee
                    ? "Поля сотрудника не прошли проверку"
                    : "Несколько полей организации не прошли проверку";
        var error = errors.get(0);
        return switch (error.getCode()) {
            case "INVALID_ENUM" -> "Неизвестный тип организации";
            case "READ_ONLY_FIELD" -> "Передано поле, управляемое сервером";
            case "UNKNOWN_FIELD" -> "Неизвестное поле тела";
            case "DUPLICATE_FIELD" -> "Одиночное поле повторяется";
            case "INVALID_TYPE" ->
                    error.getField().startsWith("officialAddress.town.")
                            ? "Неверная вложенная координата"
                            : "Поле имеет неверный тип";
            default ->
                    employee
                            ? "Поля сотрудника не прошли проверку"
                            : "Поля организации не прошли проверку";
        };
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
                                    "Вложенный объект должен содержать XML-элементы"));
                continue;
            }
            String name = child.getTagName(), field = path.isEmpty() ? name : path + "." + name;
            Rule childRule = rule.children.get(name);
            if (childRule == null || child.getNamespaceURI() != null) {
                boolean readOnly =
                        child.getNamespaceURI() == null
                                && path.isEmpty()
                                && (name.equals("id")
                                        || (rule == ORGANIZATION
                                                && Set.of("creationDate", "employeeCount")
                                                        .contains(name)));
                String hint =
                        readOnly
                                ? (name.equals("id")
                                        ? "Сервер назначает ID автоматически. Удалите поле id из тела запроса"
                                        : "Поле "
                                                + field
                                                + " заполняет сервер. Удалите его из тела запроса")
                                : "Поле "
                                        + field
                                        + " не поддерживается. Удалите его из тела запроса";
                errors.add(
                        RequestException.detail(
                                "body",
                                field,
                                readOnly ? "READ_ONLY_FIELD" : "UNKNOWN_FIELD",
                                hint));
                continue;
            }
            if (!seen.add(name))
                errors.add(
                        RequestException.detail(
                                "body",
                                field,
                                "DUPLICATE_FIELD",
                                "XML-тело должно содержать ровно один элемент " + field));
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
                                    "body",
                                    field,
                                    "INVALID_TYPE",
                                    "Элемент должен содержать текстовое значение"));
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
                                        scalarMessage(rule, childRule.kind, field, e)));
                    }
            }
        }
        rule.children.forEach(
                (name, r) -> {
                    if (r.required && !seen.contains(name)) {
                        String field = path.isEmpty() ? name : path + "." + name;
                        String hint =
                                r.kind == Kind.INT || r.kind == Kind.LONG
                                        ? "Отсутствует обязательная координата "
                                                + name
                                                + " типа "
                                                + (r.kind == Kind.INT ? "int32" : "int64")
                                        : "Передайте обязательное поле " + field;
                        errors.add(RequestException.detail("body", field, "REQUIRED", hint));
                    }
                });
        return result;
    }

    private static String scalarMessage(
            Rule parent, Kind kind, String field, IllegalArgumentException error) {
        return switch (kind) {
            case NAME ->
                    parent == EMPLOYEE
                            ? "name не может быть пустой строкой"
                            : "name обязателен и должен содержать минимум один символ";
            case DOUBLE -> "annualTurnover должен быть конечным числом строго больше 0";
            case ID ->
                    "organizationId должен быть целым числом от 1 до 9223372036854775807. "
                            + "Если работодателя нет, не передавайте этот элемент";
            case INT, LONG ->
                    "Координата "
                            + field.substring(field.lastIndexOf('.') + 1)
                            + " должна быть целым числом в диапазоне "
                            + (kind == Kind.INT ? "int32" : "int64")
                            + ". Дробные значения недопустимы";
            default -> ScalarValueRules.errorMessage(error);
        };
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
                                "Удалите атрибуты XML, включая xsi:nil"));
        }
    }
}
