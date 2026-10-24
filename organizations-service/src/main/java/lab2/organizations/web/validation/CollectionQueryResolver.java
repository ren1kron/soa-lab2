package lab2.organizations.web.validation;

import lab2.organizations.application.query.CollectionQuery;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public final class CollectionQueryResolver {
    public CollectionQuery currentQuery() {
        var request =
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                        .getRequest();
        return (CollectionQuery) request.getAttribute("collectionQuery");
    }
}
