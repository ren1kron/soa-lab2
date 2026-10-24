package lab2.organizations.web.validation;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import lab2.organizations.application.query.CollectionQuery;
import lab2.organizations.application.query.PageResult;
import lab2.organizations.web.error.RequestException;
import org.junit.jupiter.api.Test;

class CollectionQueryParserTest {
    private Map<String, String[]> params(String key, String value) {
        return Map.of(key, new String[] {value});
    }

    @Test
    void sortsNullsLastAndUsesAscendingIdTieBreaker() {
        var q = CollectionQueryParser.parse(params("sort", "-annualTurnover"), false);
        var page =
                apply(
                        q,
                        List.of(
                                Map.of("id", 3L),
                                Map.of("id", 2L, "annualTurnover", 5.0),
                                Map.of("id", 1L, "annualTurnover", 5.0)));
        assertEquals(List.of(1L, 2L, 3L), page.items().stream().map(x -> x.get("id")).toList());
    }

    @Test
    void combinesTypedFiltersAndTreatsAbsentParentsAsNull() {
        var q =
                CollectionQueryParser.parse(
                        params("filter", "type:gt:PUBLIC,officialAddress.town.x:isNull:true"),
                        false);
        var page =
                apply(
                        q,
                        List.of(
                                Map.of("id", 1L, "type", "TRUST"),
                                Map.of("id", 2L, "type", "PUBLIC"),
                                Map.of("id", 3L)));
        assertEquals(1, page.total());
        assertEquals(1L, page.items().get(0).get("id"));
    }

    @Test
    void preservesColonsInStringValues() {
        var q = CollectionQueryParser.parse(params("filter", "name:eq:a:b"), true);
        assertEquals(1, apply(q, List.of(Map.of("id", 1L, "name", "a:b"))).total());
    }

    @Test
    void excludesNullFromOrdinaryNotEquals() {
        var q = CollectionQueryParser.parse(params("filter", "organizationId:ne:1"), true);
        assertEquals(0, apply(q, List.of(Map.of("id", 1L))).total());
    }

    @Test
    void rejectsMalformedDuplicateAndConflictingSorts() {
        for (String value :
                List.of("name,name", "name,-name", "+name", "--name", "name,", "bad", " name")) {
            var e =
                    assertThrows(
                            RequestException.class,
                            () -> CollectionQueryParser.parse(params("sort", value), false));
            assertEquals(400, e.status);
            assertEquals("INVALID_SORT", e.code);
        }
    }

    @Test
    void validatesEveryFilterValue() {
        for (String value :
                List.of(
                        "id:eq:0",
                        "creationDate:eq:2024-02-30",
                        "type:in:PUBLIC|bad",
                        "coordinates:isNull:true",
                        "name:gt:a",
                        "name:contains:",
                        "id:in:1|",
                        "employees:eq:1",
                        "officialAddress.zipCode:contains:1234567890123456789",
                        "officialAddress.zipCode:startsWith:1234567890123456789")) {
            assertEquals(
                    "INVALID_FILTER",
                    assertThrows(
                                    RequestException.class,
                                    () ->
                                            CollectionQueryParser.parse(
                                                    params("filter", value), false))
                            .code);
        }
    }

    @Test
    void supportsHugePagesWithoutIntegerOverflow() {
        var q = CollectionQueryParser.parse(params("page", "999999999999999999999999"), true);
        assertEquals(new BigInteger("999999999999999999999999"), q.page());
        assertTrue(apply(q, List.of(Map.of("id", 1L))).items().isEmpty());
    }

    @Test
    void rejectsRepeatedParameters() {
        assertEquals(
                400,
                assertThrows(
                                RequestException.class,
                                () ->
                                        CollectionQueryParser.parse(
                                                Map.of("sort", new String[] {"id", "name"}), false))
                        .status);
    }

    private PageResult<Map<String, Object>> apply(
            CollectionQuery query, List<Map<String, Object>> rows) {
        return query.apply(
                rows,
                (row, path) -> {
                    Object value = row;
                    for (String part : path.split("\\.")) {
                        if (!(value instanceof Map<?, ?> map)) return null;
                        value = map.get(part);
                    }
                    return value;
                });
    }
}
