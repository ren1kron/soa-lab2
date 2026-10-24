# XML API examples

Start the deployment and run `sh scripts/export-certificates.sh` first. Replace example IDs with IDs returned by creation requests. All responses except successful deletions are XML; collection metadata appears in `X-Total-Count`, `X-Page`, and `X-Page-Size`.

```sh
ORG=https://localhost:26125
MANAGER=https://localhost:26126
```

## Organizations

```sh
# Create: 201; id and creationDate are generated.
curl --cacert .local/certs/organizations.crt -H 'Content-Type: application/xml' \
  -d '<organization><name>Example</name><coordinates><x>10</x><y>20</y></coordinates><annualTurnover>1000.5</annualTurnover><type>PUBLIC</type></organization>' "$ORG/organizations"

# List: typed filters combine with AND; sorting supports multiple fields.
curl --cacert .local/certs/organizations.crt -i -G "$ORG/organizations" \
  --data-urlencode 'filter=name:contains:Example,annualTurnover:gte:1000' \
  --data-urlencode 'sort=name,-annualTurnover' --data-urlencode 'page=1' --data-urlencode 'size=20'

# Get and replace mutable fields; omitted optional fields become absent.
curl --cacert .local/certs/organizations.crt "$ORG/organizations/1"
curl --cacert .local/certs/organizations.crt -X PUT -H 'Content-Type: application/xml' \
  -d '<organization><name>Updated</name><coordinates><x>1</x><y>2</y></coordinates></organization>' "$ORG/organizations/1"

# Delete an empty organization: 204; employees cause 409.
curl --cacert .local/certs/organizations.crt -i -X DELETE "$ORG/organizations/1"

# Delete any one organization of a type. Never automatically repeat this request.
curl --cacert .local/certs/organizations.crt -i -X DELETE "$ORG/organizations/type/PUBLIC"

# Count by enum order: PUBLIC < GOVERNMENT < TRUST < PRIVATE_LIMITED_COMPANY.
curl --cacert .local/certs/organizations.crt "$ORG/organizations/count?typeGreaterThan=PUBLIC"
```

## Employees

```sh
# Create without an employer; include organizationId to assign an existing employer.
curl --cacert .local/certs/organizations.crt -H 'Content-Type: application/xml' \
  -d '<employee><name>Alice</name></employee>' "$ORG/employees"

curl --cacert .local/certs/organizations.crt -i -G "$ORG/employees" \
  --data-urlencode 'filter=organizationId:isNull:true' --data-urlencode 'sort=-name,id' \
  --data-urlencode 'page=1' --data-urlencode 'size=20'

curl --cacert .local/certs/organizations.crt "$ORG/employees/1"
curl --cacert .local/certs/organizations.crt -X PUT -H 'Content-Type: application/xml' \
  -d '<employee><name>Alice Updated</name><organizationId>1</organizationId></employee>' "$ORG/employees/1"

# Omitting organizationId during PUT unassigns the employee.
curl --cacert .local/certs/organizations.crt -i -X DELETE "$ORG/employees/1"
```

## Manager

These operations take path/query parameters and no request body. Create the referenced organizations and employees first.

```sh
# Hire an existing unassigned employee. Already hired at the destination is a no-op.
curl --cacert .local/certs/manager.crt -X POST "$MANAGER/orgmanager/hire/1?employeeId=1"

# Transfer all employees from organization 2 to 1, then delete organization 2.
curl --cacert .local/certs/manager.crt -X POST "$MANAGER/orgmanager/acquise/1/2"
```

Explicitly repeating the same acquisition POST resumes its saved unfinished operation. For `500`, `502`, `503`, or `504`, inspect organizations and employee assignments using GET before deciding to continue. There is no automatic retry or guaranteed rollback.

## Errors

Both APIs return `<error><code>...</code><message>...</message><details>...</details></error>`. Each detail identifies `location`, `field`, `code`, and `message`. Missing data or invalid URL parameters produce `400`; well-formed XML violating a write schema produces `422`; unknown resources produce `404`; state conflicts produce `409`. Unsupported response/request formats produce `406`/`415`.

Optional XML values are omitted, never represented with `xsi:nil`. Organization and employee names must be nonempty but may contain only spaces. ZIP codes and town names may be empty. Preserve IDs and int64 coordinates as decimal strings in JavaScript clients to avoid precision loss.
