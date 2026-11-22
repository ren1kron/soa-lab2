package lab2.organizations.web.validation;

import java.math.*;
import java.time.LocalDate;
import java.util.List;

public final class ScalarValueRules {
    public enum Kind {
        ID,
        COUNT,
        LONG,
        INT,
        DOUBLE,
        NAME,
        TEXT,
        ZIP,
        TYPE,
        DATE,
        OBJECT
    }

    public static final List<String> TYPES =
            List.of("PUBLIC", "GOVERNMENT", "TRUST", "PRIVATE_LIMITED_COMPANY");

    public static Object parse(Kind kind, String value) {
        try {
            return switch (kind) {
                case ID, COUNT, LONG, INT -> {
                    if (!value.matches("[+-]?[0-9]+"))
                        throw new IllegalArgumentException(
                                "INVALID_TYPE:Требуется десятичное целое число");
                    var v = new BigInteger(value);
                    var min =
                            kind == Kind.ID
                                    ? BigInteger.ONE
                                    : kind == Kind.COUNT
                                            ? BigInteger.ZERO
                                            : BigInteger.valueOf(
                                                    kind == Kind.INT
                                                            ? Integer.MIN_VALUE
                                                            : Long.MIN_VALUE);
                    var max =
                            BigInteger.valueOf(
                                    kind == Kind.INT ? Integer.MAX_VALUE : Long.MAX_VALUE);
                    if (v.compareTo(min) < 0 || v.compareTo(max) > 0)
                        throw new IllegalArgumentException(
                                "OUT_OF_RANGE:Целое число выходит за допустимый диапазон");
                    yield kind == Kind.INT ? (Object) v.intValue() : v.longValue();
                }
                case DOUBLE -> {
                    if (!value.matches(
                            "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                        throw new IllegalArgumentException(
                                "OUT_OF_RANGE:Укажите конечное число строго больше 0");
                    double v = Double.parseDouble(value);
                    if (!Double.isFinite(v) || v <= 0)
                        throw new IllegalArgumentException(
                                "OUT_OF_RANGE:Укажите конечное число строго больше 0");
                    yield v;
                }
                case TYPE -> {
                    if (!TYPES.contains(value))
                        throw new IllegalArgumentException(
                                "INVALID_ENUM:Выберите PUBLIC, GOVERNMENT, TRUST или PRIVATE_LIMITED_COMPANY. Используйте заглавные буквы");
                    yield value;
                }
                case NAME -> {
                    if (value.isEmpty())
                        throw new IllegalArgumentException(
                                "TOO_SHORT:Требуется минимум один символ");
                    yield value;
                }
                case ZIP -> {
                    if (value.codePointCount(0, value.length()) > 18)
                        throw new IllegalArgumentException(
                                "TOO_LONG:Допустимо не более 18 символов");
                    yield value;
                }
                case DATE -> {
                    if (!value.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                        throw new IllegalArgumentException(
                                "INVALID_FORMAT:Укажите дату в формате YYYY-MM-DD с годом от 0001 до 9999");
                    yield LocalDate.parse(value);
                }
                case TEXT -> value;
                case OBJECT ->
                        throw new IllegalArgumentException(
                                "UNKNOWN_OPERATOR:Для объектов доступен только isNull");
            };
        } catch (java.time.DateTimeException | NumberFormatException e) {
            throw new IllegalArgumentException("INVALID_TYPE:Значение имеет неверный тип");
        }
    }

    public static String errorCode(IllegalArgumentException e) {
        return e.getMessage().split(":", 2)[0];
    }

    public static String errorMessage(IllegalArgumentException e) {
        return e.getMessage().split(":", 2)[1];
    }
}
