package com.umutvarol.restaurant.business;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The second wall between businesses (docs/adr/0001-multi-business.md): PostgreSQL row-level security on every table
 * that has a {@code business_id}. The first wall is Hibernate's tenant filter; this one holds even for a query that
 * bypasses it (native SQL, a bulk update, a JDBC call). Each transaction tells the database its business
 * ({@code sofra.business_id}, set by BranchTransactionManager and reset when the transaction ends); a row of another
 * business is then neither seen nor written. Work that names no business (migrations, start-up) and work across all
 * businesses ({@code BusinessContext.ALL}, "0") pass, as they do in the application.
 * <p>
 * Applied after every migration, so a table added later is covered without anyone remembering to. FORCE makes it hold
 * for the tables' owner too, which is the user the application connects as. A superuser (or a role with BYPASSRLS),
 * which is what the official PostgreSQL image makes of {@code POSTGRES_USER}, ignores row-level security altogether;
 * for such a user a role without that power is kept ({@value #TENANT_ROLE}, no login, only reading and writing rows),
 * and each business's transaction runs as it ({@code SET LOCAL ROLE}). Other databases (H2 in tests) are left as
 * they are.
 */
@Component
class RowLevelSecurity implements Callback {

    static final String SETTING = "sofra.business_id";
    /** The role a business's transaction takes on when the connecting user would otherwise bypass the policy. */
    static final String TENANT_ROLE = "sofra_tenant";
    /** Whether the connecting user bypasses row-level security, and whether {@link #TENANT_ROLE} exists. */
    static final String BYPASS_QUERY = """
            SELECT r.rolsuper OR r.rolbypassrls, EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s')
            FROM pg_roles r WHERE r.rolname = current_user""".formatted(TENANT_ROLE);
    private static final Logger log = LoggerFactory.getLogger(RowLevelSecurity.class);
    private static final String POLICY = "sofra_business_isolation";

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public String getCallbackName() {
        return "row-level security";
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            Connection connection = context.getConnection();
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
            apply(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Row-level security could not be set up", ex);
        }
    }

    private static String schema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT current_schema()")) {
            row.next();
            return row.getString(1).replace("\"", "\"\"");
        }
    }

    static void apply(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT c.table_name FROM information_schema.columns c
                     JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                     WHERE c.column_name = 'business_id' AND c.table_schema = current_schema() AND t.table_type = 'BASE TABLE'
                     ORDER BY c.table_name""")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE OR REPLACE FUNCTION sofra_business_visible(row_business bigint) RETURNS boolean
                        LANGUAGE sql STABLE AS $$
                        SELECT CASE coalesce(current_setting('%1$s', true), '')
                                   WHEN '' THEN true
                                   WHEN '0' THEN true
                                   ELSE row_business = current_setting('%1$s', true)::bigint
                               END
                    $$""".formatted(SETTING));
            for (String table : tables) {
                String name = "\"" + table.replace("\"", "\"\"") + "\"";
                statement.execute("ALTER TABLE " + name + " ENABLE ROW LEVEL SECURITY");
                statement.execute("ALTER TABLE " + name + " FORCE ROW LEVEL SECURITY");
                statement.execute("DROP POLICY IF EXISTS " + POLICY + " ON " + name);
                statement.execute("CREATE POLICY " + POLICY + " ON " + name
                        + " USING (sofra_business_visible(business_id)) WITH CHECK (sofra_business_visible(business_id))");
            }
        }
        boolean bypasses;
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(BYPASS_QUERY)) {
            bypasses = row.next() && row.getBoolean(1);
        }
        if (bypasses) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        DO $$ BEGIN
                            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
                                CREATE ROLE %1$s NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
                            END IF;
                        END $$""".formatted(TENANT_ROLE));
                statement.execute("GRANT USAGE ON SCHEMA \"" + schema(connection) + "\" TO " + TENANT_ROLE);
                statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA \"" + schema(connection)
                        + "\" TO " + TENANT_ROLE);
                statement.execute("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA \"" + schema(connection)
                        + "\" TO " + TENANT_ROLE);
            }
        }
        log.info("Row-level security: {} tables limited to their business{}", tables.size(),
                bypasses ? " (the database user bypasses it, so businesses' transactions run as " + TENANT_ROLE + ")" : "");
    }
}
