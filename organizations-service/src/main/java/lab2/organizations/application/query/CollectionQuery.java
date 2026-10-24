package lab2.organizations.application.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.function.BiFunction;
import lab2.organizations.generated.model.OrganizationType;

/** Parsed query data and execution, independent of HTTP parsing and serialization. */
public final class CollectionQuery {
    public record Order(String field, boolean descending) {}

    public record Condition(String field, String operator, List<Object> values) {
        public Condition {
            values = List.copyOf(values);
        }
    }

    private final BigInteger page;
    private final int size;
    private final List<Order> orders;
    private final List<Condition> conditions;

    public CollectionQuery(
            BigInteger page, int size, List<Order> orders, List<Condition> conditions) {
        this.page = page;
        this.size = size;
        this.orders = List.copyOf(orders);
        this.conditions = List.copyOf(conditions);
    }

    public BigInteger page() {
        return page;
    }

    public int size() {
        return size;
    }

    public <T> PageResult<T> apply(List<T> items, BiFunction<T, String, Object> fields) {
        var selected =
                items.stream()
                        .filter(
                                item ->
                                        conditions.stream()
                                                .allMatch(
                                                        condition ->
                                                                matches(
                                                                        condition,
                                                                        fields.apply(
                                                                                item,
                                                                                condition
                                                                                        .field()))))
                        .sorted((a, b) -> compare(a, b, fields))
                        .toList();
        var offset = page.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(size));
        if (offset.compareTo(BigInteger.valueOf(selected.size())) >= 0) {
            return new PageResult<>(List.of(), selected.size(), page, size);
        }
        int start = offset.intValueExact();
        int end = (int) Math.min(selected.size(), (long) start + size);
        return new PageResult<>(selected.subList(start, end), selected.size(), page, size);
    }

    private <T> int compare(T a, T b, BiFunction<T, String, Object> fields) {
        for (var order : orders) {
            Object av = fields.apply(a, order.field()), bv = fields.apply(b, order.field());
            int comparison;
            if (av == null || bv == null) {
                comparison = av == bv ? 0 : av == null ? 1 : -1;
            } else {
                comparison = compareValue(av, bv, order.field());
                if (order.descending()) comparison = -comparison;
            }
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static boolean matches(Condition condition, Object actual) {
        String operator = condition.operator();
        Object expected = condition.values().get(0);
        if (operator.equals("isNull")) return (actual == null) == (Boolean) expected;
        if (actual == null) return false;
        if (operator.equals("contains")) return actual.toString().contains(expected.toString());
        if (operator.equals("startsWith")) return actual.toString().startsWith(expected.toString());
        if (operator.equals("in")) {
            return condition.values().stream()
                    .anyMatch(value -> compareValue(actual, value, condition.field()) == 0);
        }
        int comparison = compareValue(actual, expected, condition.field());
        return switch (operator) {
            case "eq" -> comparison == 0;
            case "ne" -> comparison != 0;
            case "gt" -> comparison > 0;
            case "gte" -> comparison >= 0;
            case "lt" -> comparison < 0;
            case "lte" -> comparison <= 0;
            default -> throw new IllegalArgumentException("Unknown parsed operator: " + operator);
        };
    }

    private static int compareValue(Object a, Object b, String field) {
        if (field.equals("type")) {
            return Integer.compare(
                    OrganizationType.fromValue(a.toString()).ordinal(),
                    OrganizationType.fromValue(b.toString()).ordinal());
        }
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        }
        return a.toString().compareTo(b.toString());
    }
}
