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
                            postgres = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
                        }
                        if (postgres) {
                            try (java.sql.PreparedStatement set = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
                                set.setString(1, RowLevelSecurity.SETTING);
                                set.setString(2, Long.toString(business));
                                set.execute();
                            }
                        }
                    });
                }
            }
        };
    }
}
