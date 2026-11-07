BEGIN;
SELECT set_config('search_path', format('%I', :'schema'), true);
SELECT pg_advisory_xact_lock(261250);

CREATE TEMP TABLE seed_organizations (
    number INTEGER PRIMARY KEY,
    id BIGINT NOT NULL
) ON COMMIT DROP;

WITH inserted AS (
    INSERT INTO organizations (
        name, coordinates_x, coordinates_y, creation_date, annual_turnover,
        type, address_zip_code, town_x, town_y, town_name
    )
    SELECT
        'orga' || i,
        CASE
            WHEN i = 29 THEN '9223372036854775807'::BIGINT
            WHEN i = 30 THEN '-9223372036854775808'::BIGINT
            ELSE (i - 15)::BIGINT * 100
        END,
        (i - 10) * 25,
        DATE '2020-01-01' + i * 73,
        CASE WHEN i % 6 = 0 THEN NULL ELSE i * 12500.75 END,
        (CASE (i - 1) % 5
            WHEN 0 THEN 'PUBLIC'
            WHEN 1 THEN 'GOVERNMENT'
            WHEN 2 THEN 'TRUST'
            WHEN 3 THEN 'PRIVATE_LIMITED_COMPANY'
            ELSE NULL
        END)::organization_type,
        CASE
            WHEN i % 3 = 0 THEN NULL
            WHEN i % 10 = 0 THEN ''
            ELSE (190000 + i)::TEXT
        END,
        CASE WHEN i % 3 <> 0 AND i % 4 <> 0 THEN i::BIGINT * 10 END,
        CASE WHEN i % 3 <> 0 AND i % 4 <> 0 THEN i::BIGINT * -20 END,
        CASE WHEN i % 3 <> 0 AND i % 4 <> 0 THEN 'town' || i END
    FROM generate_series(1, 30) AS i
    RETURNING id, name
)
INSERT INTO seed_organizations (number, id)
SELECT substring(name FROM 5)::INTEGER, id FROM inserted;

INSERT INTO employees (name, organization_id)
SELECT
    'employee' || i,
    CASE WHEN i % 6 = 0 THEN NULL ELSE organization.id END
FROM generate_series(1, 90) AS i
JOIN seed_organizations AS organization
    ON organization.number = CASE WHEN i <= 25 THEN 1 ELSE 2 + (i - 26) % 23 END;

COMMIT;
