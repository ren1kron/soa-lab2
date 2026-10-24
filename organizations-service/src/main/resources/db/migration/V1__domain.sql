CREATE TYPE organization_type AS ENUM (
    'PUBLIC', 'GOVERNMENT', 'TRUST', 'PRIVATE_LIMITED_COMPANY'
);

CREATE TABLE organizations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY CHECK (id > 0),
    name TEXT NOT NULL,
    coordinates_x BIGINT NOT NULL,
    coordinates_y INTEGER NOT NULL,
    creation_date DATE NOT NULL,
    annual_turnover DOUBLE PRECISION,
    type organization_type,
    address_zip_code TEXT,
    town_x BIGINT,
    town_y BIGINT,
    town_name TEXT,
    CONSTRAINT organizations_name_not_empty CHECK (char_length(name) > 0),
    CONSTRAINT organizations_creation_date_range
        CHECK (creation_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    CONSTRAINT organizations_turnover_positive_finite
        CHECK (annual_turnover IS NULL OR (annual_turnover > 0 AND annual_turnover < 'Infinity'::DOUBLE PRECISION)),
    CONSTRAINT organizations_zip_length
        CHECK (address_zip_code IS NULL OR char_length(address_zip_code) <= 18),
    CONSTRAINT organizations_town_complete
        CHECK (
            (town_x IS NULL AND town_y IS NULL AND town_name IS NULL)
            OR
            (address_zip_code IS NOT NULL AND town_x IS NOT NULL AND town_y IS NOT NULL AND town_name IS NOT NULL)
        )
);
CREATE INDEX organizations_type_id_idx ON organizations(type, id);

CREATE TABLE employees (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY CHECK (id > 0),
    name TEXT NOT NULL CHECK (length(name) > 0),
    organization_id BIGINT REFERENCES organizations(id) ON DELETE RESTRICT
);
CREATE INDEX employees_organization_idx ON employees(organization_id);
