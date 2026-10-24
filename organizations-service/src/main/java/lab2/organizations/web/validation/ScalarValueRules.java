package lab2.organizations.web.validation;

import java.math.*;
import java.time.LocalDate;
import java.util.List;

public final class ScalarValueRules {
    public enum Kind {
        ID,
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
                case ID, LONG, INT -> {
                    if (!value.matches("[+-]?[0-9]+"))
                        throw new IllegalArgumentException(
                                "INVALID_TYPE:Expected a decimal integer");
                    var v = new BigInteger(value);
                    var min =
                            kind == Kind.ID
                                    ? BigInteger.ONE
                                    : BigInteger.valueOf(
                                            kind == Kind.INT ? Integer.MIN_VALUE : Long.MIN_VALUE);
                    var max =
                            BigInteger.valueOf(
                                    kind == Kind.INT ? Integer.MAX_VALUE : Long.MAX_VALUE);
                    if (v.compareTo(min) < 0 || v.compareTo(max) > 0)
                        throw new IllegalArgumentException(
                                "OUT_OF_RANGE:Integer is outside its declared range");
                    yield kind == Kind.INT ? (Object) v.intValue() : v.longValue();
                }
                case DOUBLE -> {
                    if (!value.matches(
                            "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                        throw new IllegalArgumentException(
                                "OUT_OF_RANGE:Expected a finite positive double");
                    double v = Double.parseDouble(value);
                    if (!Double.isFinite(v) || v <= 0)
                        throw new IllegalArgumentException(
                                "OUT_OF_RANGE:Expected a finite positive double");
                    yield v;
                }
                case TYPE -> {
                    if (!TYPES.contains(value))
                        throw new IllegalArgumentException(
                                "INVALID_ENUM:Unknown organization type");
                    yield value;
                }
                case NAME -> {
                    if (value.isEmpty())
                        throw new IllegalArgumentException(
                                "TOO_SHORT:At least one character is required");
                    yield value;
                }
                case ZIP -> {
                    if (value.codePointCount(0, value.length()) > 18)
                        throw new IllegalArgumentException(
                                "TOO_LONG:At most 18 characters are allowed");
                    yield value;
                }
                case DATE -> {
                    if (!value.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                        throw new IllegalArgumentException(
                                "INVALID_FORMAT:Expected YYYY-MM-DD in years 0001-9999");
                    yield LocalDate.parse(value);
                }
                case TEXT -> value;
                case OBJECT ->
                        throw new IllegalArgumentException(
                                "INVALID_OPERATOR:Objects support only isNull");
            };
        } catch (java.time.DateTimeException | NumberFormatException e) {
            throw new IllegalArgumentException(
                    "INVALID_TYPE:Value does not match the declared type");
        }
    }

    public static String errorCode(IllegalArgumentException e) {
        return e.getMessage().split(":", 2)[0];
    }
}
