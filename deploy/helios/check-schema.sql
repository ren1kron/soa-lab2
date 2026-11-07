-- Both services share the schema named after the connected user, which may hold unrelated work.
-- This check changes nothing: Flyway creates the tables once it has passed.
DO $$
DECLARE
    conflicts TEXT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_namespace
        WHERE nspname = current_user
          AND has_schema_privilege(oid, 'USAGE')
          AND has_schema_privilege(oid, 'CREATE')
    ) THEN
        RAISE EXCEPTION 'Schema % must exist and allow the connected user to create objects', current_user;
    END IF;

    -- A service's history table shows that the objects are its own from an earlier deployment.
    SELECT string_agg(object_name, ', ' ORDER BY object_name) INTO conflicts
    FROM (VALUES
        ('organizations', 'organizations_schema_history'),
        ('organizations_type_id_idx', 'organizations_schema_history'),
        ('organization_type', 'organizations_schema_history'),
        ('employees', 'organizations_schema_history'),
        ('employees_organization_idx', 'organizations_schema_history'),
        ('acquisition_journal', 'manager_schema_history')
    ) AS expected(object_name, history_table)
    WHERE to_regclass(format('%I.%I', current_user, history_table)) IS NULL
      AND (to_regclass(format('%I.%I', current_user, object_name)) IS NOT NULL
           OR to_regtype(format('%I.%I', current_user, object_name)) IS NOT NULL);

    IF conflicts IS NOT NULL THEN
        RAISE EXCEPTION 'Schema % already contains objects this project needs: %. Nothing was changed; rename or remove them after checking what they are.', current_user, conflicts;
    END IF;
END $$;
