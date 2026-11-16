package com.umutvarol.restaurant.business;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns on the branch filter for every transaction of a request that works in one branch, so lists show that
 * branch's tables, orders and bills. Without a branch (a single restaurant, or a chain owner looking at all of them)
 * nothing is filtered beyond the business.
 */
@Configuration(proxyBeanMethods = false)
class BranchTransactionManager {

    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory) {
            private volatile Boolean postgres;
            /** Whether a business's transaction has to take on RowLevelSecurity.TENANT_ROLE (see there). */
            private volatile boolean asTenant;

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                super.doBegin(transaction, definition);
                if (!(TransactionSynchronizationManager.getResource(entityManagerFactory) instanceof EntityManagerHolder holder)) {
                    return;
                }
                Session session = holder.getEntityManager().unwrap(Session.class);
                Long branch = BranchContext.current();
                if (branch != null) {
                    session.enableFilter(Branch.FILTER).setParameter("branch", branch);
                }
                // the second wall (RowLevelSecurity): PostgreSQL itself keeps this transaction to its business
                Long business = BusinessContext.current();
                if (business != null && postgres != Boolean.FALSE) {
                    session.doWork(connection -> {
                        if (postgres == null) {
                            boolean isPostgres = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
                            if (isPostgres) {
                                asTenant = tenantRoleNeeded(connection);
                            }
                            postgres = isPostgres;
                        }
                        if (postgres) {
                            try (java.sql.PreparedStatement set = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
                                set.setString(1, RowLevelSecurity.SETTING);
                                set.setString(2, Long.toString(business));
                                set.execute();
                            }
                            if (asTenant) {
                                // ends with the transaction, like the setting above
                                try (java.sql.Statement role = connection.createStatement()) {
                                    role.execute("SET LOCAL ROLE " + RowLevelSecurity.TENANT_ROLE);
                                }
                            }
                        }
                    });
                }
            }
        };
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BranchTransactionManager.class);

    /** A superuser or BYPASSRLS user ignores the policies; its businesses' transactions then run as the tenant role. */
    private static boolean tenantRoleNeeded(java.sql.Connection connection) throws java.sql.SQLException {
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet row = statement.executeQuery(RowLevelSecurity.BYPASS_QUERY)) {
            if (!row.next() || !row.getBoolean(1)) {
                return false;
            }
            if (!row.getBoolean(2)) {
                log.warn("The database user bypasses row-level security and the role {} does not exist: only Hibernate's "
                        + "tenant filter separates businesses. Run the migrations as this user, or connect as a user "
                        + "that is not a superuser.", RowLevelSecurity.TENANT_ROLE);
                return false;
            }
            return true;
        }
    }
}
