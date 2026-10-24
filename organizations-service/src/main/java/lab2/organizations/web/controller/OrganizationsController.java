package lab2.organizations.web.controller;

import java.math.BigInteger;
import java.util.*;
import lab2.organizations.application.organization.OrganizationService;
import lab2.organizations.generated.api.OrganizationsApi;
import lab2.organizations.generated.model.*;
import lab2.organizations.web.validation.CollectionQueryResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.*;

@RestController
public final class OrganizationsController implements OrganizationsApi {
    private final OrganizationService service;
    private final CollectionQueryResolver queries;

    public OrganizationsController(OrganizationService service, CollectionQueryResolver queries) {
        this.service = service;
        this.queries = queries;
    }

    @Override
    public ResponseEntity<Organization> createOrganization(OrganizationWrite input) {
        return ResponseEntity.status(201).body(service.create(input));
    }

    @Override
    public ResponseEntity<Organization> getOrganization(Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @Override
    public ResponseEntity<Organization> updateOrganization(Long id, OrganizationWrite input) {
        return ResponseEntity.ok(service.replace(id, input));
    }

    @Override
    public ResponseEntity<Void> deleteOrganization(Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> deleteOneOrganizationByType(OrganizationType type) {
        service.deleteByType(type);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Count> countOrganizationsByTypeGreaterThan(
            OrganizationType typeGreaterThan) {
        return ResponseEntity.ok(new Count(service.countByTypeGreaterThan(typeGreaterThan)));
    }

    @Override
    public ResponseEntity<List<Organization>> listOrganizations(
            BigInteger page, BigInteger size, Set<String> sort, List<String> filter) {
        return CollectionResponses.page(service.list(queries.currentQuery()));
    }
}
