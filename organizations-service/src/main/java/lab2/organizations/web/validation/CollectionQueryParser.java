package lab2.organizations.web.validation;

import java.math.BigInteger;
import java.util.*;
import lab2.organizations.application.query.CollectionQuery;
import lab2.organizations.application.query.CollectionQuery.Condition;
import lab2.organizations.application.query.CollectionQuery.Order;
import lab2.organizations.generated.model.ErrorDetail;
import lab2.organizations.web.error.RequestException;
import lab2.organizations.web.validation.ScalarValueRules.Kind;

public final class CollectionQueryParser {
    private CollectionQueryParser() {}

    private record Field(Kind kind, boolean nullable) {}

    private static Map<String, Field> fields(boolean employees) {
        var f = new LinkedHashMap<String, Field>();
        f.put("id", new Field(Kind.ID, false));
        f.put("name", new Field(Kind.NAME, false));
        if (employees) f.put("organizationId", new Field(Kind.ID, true));
        else {
            f.put("coordinates.x", new Field(Kind.LONG, false));
            f.put("coordinates.y", new Field(Kind.INT, false));
            f.put("creationDate", new Field(Kind.DATE, false));
            f.put("annualTurnover", new Field(Kind.DOUBLE, true));
            f.put("type", new Field(Kind.TYPE, true));
            f.put("officialAddress", new Field(Kind.OBJECT, true));
            f.put("officialAddress.zipCode", new Field(Kind.ZIP, true));
            f.put("officialAddress.town", new Field(Kind.OBJECT, true));
            f.put("officialAddress.town.x", new Field(Kind.LONG, true));
            f.put("officialAddress.town.y", new Field(Kind.LONG, true));
            f.put("officialAddress.town.name", new Field(Kind.TEXT, true));
        }
        return f;
    }

    public static CollectionQuery parse(Map<String, String[]> params, boolean employees) {
        var errors = new ArrayList<ErrorDetail>();
        params.forEach(
                (key, values) -> {
                    if (!Set.of("page", "size", "sort", "filter").contains(key))
                        errors.add(
                                RequestException.detail(
                                        "query", key, "UNKNOWN_FIELD", "Unknown query parameter"));
                    if (values.length != 1)
                        errors.add(
                                RequestException.detail(
                                        "query",
                                        key,
                                        "DUPLICATE_PARAMETER",
                                        "Pass this parameter once"));
                });
        BigInteger page = BigInteger.ONE;
        int size = 20;
        try {
            page = positive(first(params, "page", "1"));
        } catch (IllegalArgumentException e) {
            errors.add(
                    RequestException.detail(
                            "query",
                            "page",
                            "OUT_OF_RANGE",
                            "page must be a decimal integer >= 1"));
        }
        try {
            var s = positive(first(params, "size", "20"));
            if (s.compareTo(BigInteger.valueOf(100)) > 0) throw new IllegalArgumentException();
            size = s.intValueExact();
        } catch (IllegalArgumentException | ArithmeticException e) {
            errors.add(
                    RequestException.detail(
                            "query",
                            "size",
                            "OUT_OF_RANGE",
                            "size must be an integer from 1 to 100"));
        }
        Map<String, Field> fields = fields(employees);
        var orders = new ArrayList<Order>();
        var seen = new HashMap<String, Boolean>();
        String[] sorts = first(params, "sort", "id").split(",", -1);
        for (int i = 0; i < sorts.length; i++) {
            String term = sorts[i], field = term.startsWith("-") ? term.substring(1) : term;
            boolean desc = term.startsWith("-");
            String code = null;
            if (!term.matches("-?[A-Za-z][A-Za-z0-9.]*")) code = "INVALID_FORMAT";
            else if (!fields.containsKey(field) || fields.get(field).kind == Kind.OBJECT)
                code = "UNKNOWN_FIELD";
            else if (seen.containsKey(field))
                code = seen.get(field) == desc ? "DUPLICATE_SORT" : "CONFLICTING_SORT";
            if (code != null)
                errors.add(
                        RequestException.detail(
                                "query",
                                "sort[" + i + "]",
                                code,
                                "Sort by a declared field once, optionally prefixed with -"));
            else {
                orders.add(new Order(field, desc));
                seen.put(field, desc);
            }
        }
        if (!seen.containsKey("id")) orders.add(new Order("id", false));
        var conditions = new ArrayList<Condition>();
        if (params.containsKey("filter")) {
            var terms = first(params, "filter", "").split(",", -1);
            for (int i = 0; i < terms.length; i++) {
                try {
                    conditions.add(filter(terms[i], fields));
                } catch (IllegalArgumentException e) {
                    errors.add(
                            RequestException.detail(
                                    "query",
                                    "filter[" + i + "]",
                                    e.getMessage().contains(":")
                                            ? ScalarValueRules.errorCode(e)
                                            : "INVALID_FORMAT",
                                    e.getMessage()));
                }
            }
        }
        if (!errors.isEmpty()) {
            String code =
                    errors.stream().allMatch(e -> e.getField().startsWith("sort["))
                            ? "INVALID_SORT"
                            : errors.stream().allMatch(e -> e.getField().startsWith("filter["))
                                    ? "INVALID_FILTER"
                                    : "INVALID_REQUEST";
            throw new RequestException(400, code, "Invalid collection parameters", errors);
        }
        return new CollectionQuery(page, size, orders, conditions);
    }

    private static String first(Map<String, String[]> map, String key, String fallback) {
        return map.containsKey(key) && map.get(key).length > 0 ? map.get(key)[0] : fallback;
    }

    private static BigInteger positive(String s) {
        if (!s.matches("[0-9]+")) throw new IllegalArgumentException();
        var n = new BigInteger(s);
        if (n.signum() <= 0) throw new IllegalArgumentException();
        return n;
    }

    private static Condition filter(String term, Map<String, Field> fields) {
        String[] parts = term.split(":", 3);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty())
            throw new IllegalArgumentException("INVALID_FORMAT:Expected field:operator:value");
        var field = fields.get(parts[0]);
        if (field == null) throw new IllegalArgumentException("UNKNOWN_FIELD:Unknown filter field");
        String op = parts[1], value = parts[2], path = parts[0];
        if (op.equals("isNull")) {
            if (!field.nullable)
                throw new IllegalArgumentException(
                        "INVALID_OPERATOR:isNull requires an optional field or descendant");
            if (!Set.of("true", "false").contains(value))
                throw new IllegalArgumentException("INVALID_TYPE:isNull requires true or false");
            return new Condition(path, op, List.of(Boolean.parseBoolean(value)));
        }
        boolean string = Set.of(Kind.NAME, Kind.TEXT, Kind.ZIP).contains(field.kind);
        var allowed =
                string
                        ? Set.of("eq", "ne", "contains", "startsWith", "in")
                        : Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in");
        if (field.kind == Kind.OBJECT || !allowed.contains(op))
            throw new IllegalArgumentException(
                    "INVALID_OPERATOR:Operator is unavailable for this field");
        if (op.equals("contains") || op.equals("startsWith")) {
            if (value.isEmpty())
                throw new IllegalArgumentException("TOO_SHORT:Search text must not be empty");
            ScalarValueRules.parse(field.kind, value);
            return new Condition(path, op, List.of(value));
        }
        var values = new ArrayList<Object>();
        for (String item : op.equals("in") ? value.split("\\|", -1) : new String[] {value}) {
            if (op.equals("in") && item.isEmpty())
                throw new IllegalArgumentException("INVALID_FORMAT:in requires nonempty values");
            values.add(ScalarValueRules.parse(field.kind, item));
        }
        return new Condition(path, op, values);
    }
}
