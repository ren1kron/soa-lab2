package lab2.organizations.web.validation;

import java.math.BigInteger;
import java.util.*;
import lab2.organizations.application.query.CollectionQuery;
import lab2.organizations.application.query.CollectionQuery.Condition;
import lab2.organizations.application.query.CollectionQuery.Order;
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
            f.put("employeeCount", new Field(Kind.COUNT, false));
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
        var errors = new RequestErrors();
        errors.checkParameters(params, Set.of("page", "size", "sort", "filter"));
        BigInteger page = BigInteger.ONE;
        int size = 20;
        try {
            page = positive(first(params, "page", "1"));
        } catch (IllegalArgumentException e) {
            errors.invalid(
                    "Неверные параметры пагинации",
                    "query",
                    "page",
                    "OUT_OF_RANGE",
                    "page должен быть целым числом не меньше 1");
        }
        try {
            var s = positive(first(params, "size", "20"));
            if (s.compareTo(BigInteger.valueOf(100)) > 0) throw new IllegalArgumentException();
            size = s.intValueExact();
        } catch (IllegalArgumentException | ArithmeticException e) {
            errors.invalid(
                    "Неверные параметры пагинации",
                    "query",
                    "size",
                    "OUT_OF_RANGE",
                    "size должен быть целым числом от 1 до 100 включительно");
        }
        Map<String, Field> fields = fields(employees);
        var orders = new ArrayList<Order>();
        var seen = new HashMap<String, Boolean>();
        String sort = first(params, "sort", "id");
        String[] sorts = sort.split(",", -1);
        for (int i = 0; i < sorts.length; i++) {
            String term = sorts[i], field = term.startsWith("-") ? term.substring(1) : term;
            boolean desc = term.startsWith("-");
            String code = null;
            if (!term.matches("-?[A-Za-z][A-Za-z0-9.]*")) code = "INVALID_FORMAT";
            else if (!fields.containsKey(field) || fields.get(field).kind == Kind.OBJECT)
                code = "UNKNOWN_FIELD";
            else if (seen.containsKey(field))
                code = seen.get(field) == desc ? "DUPLICATE_SORT" : "CONFLICTING_SORT";
            if (code != null) sortError(errors, sort, i, term, field, code);
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
                    String reason = ScalarValueRules.errorCode(e);
                    String summary =
                            switch (reason) {
                                case "UNKNOWN_OPERATOR" -> "Оператор фильтра не подходит полю";
                                case "UNKNOWN_FIELD" -> "Неизвестное поле фильтра";
                                case "INVALID_TYPE",
                                                "OUT_OF_RANGE",
                                                "INVALID_ENUM",
                                                "TOO_SHORT",
                                                "TOO_LONG" ->
                                        "Значение фильтра имеет неверный тип";
                                default -> "Некорректный фильтр";
                            };
                    errors.add(
                            "INVALID_FILTER",
                            summary,
                            "query",
                            "filter[" + i + "]",
                            reason,
                            ScalarValueRules.errorMessage(e));
                }
            }
        }
        errors.requireValid();
        return new CollectionQuery(page, size, orders, conditions);
    }

    private static void sortError(
            RequestErrors errors, String sort, int index, String term, String field, String code) {
        String summary;
        String hint;
        switch (code) {
            case "UNKNOWN_FIELD" -> {
                summary = "Неизвестное поле сортировки";
                hint =
                        "По полю "
                                + field
                                + " сортировать нельзя. Выберите поле из списка допустимых значений sort";
            }
            case "DUPLICATE_SORT" -> {
                summary = "Поле сортировки повторяется";
                hint = "Поле " + field + " указано в sort дважды. Оставьте его только один раз";
            }
            case "CONFLICTING_SORT" -> {
                summary = "Противоречащие направления сортировки";
                hint =
                        "Для поля "
                                + field
                                + " указаны два направления сортировки. Выберите "
                                + field
                                + " или -"
                                + field;
            }
            default -> {
                if (term.isEmpty()) {
                    summary = "Пустой элемент сортировки";
                    hint =
                            index == 1
                                    ? "В sort="
                                            + sort
                                            + " пропущено поле после первой запятой. Удалите лишнюю запятую"
                                    : "Укажите поле в позиции "
                                            + (index + 1)
                                            + " или удалите лишнюю запятую";
                } else {
                    summary = "Некорректное направление сортировки";
                    String name = term.replaceFirst("^-+", "");
                    hint =
                            term.startsWith("--") && !name.isEmpty()
                                    ? "В sort="
                                            + sort
                                            + " разрешён только один ведущий минус: используйте "
                                            + name
                                            + " или -"
                                            + name
                                    : "Укажите допустимое поле без пробелов, с одним ведущим минусом для убывания";
                }
            }
        }
        errors.add("INVALID_SORT", summary, "query", "sort[" + index + "]", code, hint);
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
            throw new IllegalArgumentException(
                    "INVALID_FORMAT:Фильтр должен иметь вид поле:оператор:значение, например name:eq:Иван");
        var field = fields.get(parts[0]);
        if (field == null)
            throw new IllegalArgumentException(
                    "UNKNOWN_FIELD:Поле " + parts[0] + " недоступно для фильтрации");
        String op = parts[1], value = parts[2], path = parts[0];
        if (op.equals("isNull")) {
            if (!field.nullable)
                throw new IllegalArgumentException(
                        "UNKNOWN_OPERATOR:Оператор isNull доступен для необязательных полей и их вложенных полей");
            if (!Set.of("true", "false").contains(value))
                throw new IllegalArgumentException(
                        "INVALID_TYPE:Укажите true или false для isNull");
            return new Condition(path, op, List.of(Boolean.parseBoolean(value)));
        }
        boolean string = Set.of(Kind.NAME, Kind.TEXT, Kind.ZIP).contains(field.kind);
        var allowed =
                string
                        ? Set.of("eq", "ne", "contains", "startsWith", "in")
                        : Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in");
        if (field.kind == Kind.OBJECT || !allowed.contains(op))
            throw new IllegalArgumentException(
                    "UNKNOWN_OPERATOR:Оператор "
                            + op
                            + " не подходит для поля "
                            + path
                            + ". "
                            + (field.kind == Kind.OBJECT
                                    ? "Используйте isNull"
                                    : string
                                            ? "Используйте eq, ne, contains, startsWith или in"
                                            : "Используйте eq, ne, gt, gte, lt, lte или in"));
        if (op.equals("contains") || op.equals("startsWith")) {
            if (value.isEmpty())
                throw new IllegalArgumentException("TOO_SHORT:Укажите непустую строку поиска");
            ScalarValueRules.parse(field.kind, value);
            return new Condition(path, op, List.of(value));
        }
        var values = new ArrayList<Object>();
        for (String item : op.equals("in") ? value.split("\\|", -1) : new String[] {value}) {
            if (op.equals("in") && item.isEmpty())
                throw new IllegalArgumentException(
                        "INVALID_FORMAT:Оператор in требует непустые значения");
            try {
                values.add(ScalarValueRules.parse(field.kind, item));
            } catch (IllegalArgumentException e) {
                if (field.kind == Kind.ID && ScalarValueRules.errorCode(e).equals("INVALID_TYPE"))
                    throw new IllegalArgumentException(
                            "INVALID_TYPE:В "
                                    + term
                                    + " значение "
                                    + path
                                    + " должно быть положительным десятичным int64");
                throw e;
            }
        }
        return new Condition(path, op, values);
    }
}
