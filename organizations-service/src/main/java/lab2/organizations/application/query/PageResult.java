package lab2.organizations.application.query;

import java.math.BigInteger;
import java.util.List;

public record PageResult<T>(List<T> items, long total, BigInteger page, int size) {
    public PageResult {
        items = List.copyOf(items);
    }
}
