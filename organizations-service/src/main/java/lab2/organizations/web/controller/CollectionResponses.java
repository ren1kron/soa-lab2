package lab2.organizations.web.controller;

import java.util.List;
import lab2.organizations.application.query.PageResult;
import org.springframework.http.ResponseEntity;

final class CollectionResponses {
    private CollectionResponses() {}

    static <T> ResponseEntity<List<T>> page(PageResult<T> result) {
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(result.total()))
                .header("X-Page", result.page().toString())
                .header("X-Page-Size", Integer.toString(result.size()))
                .body(result.items());
    }
}
