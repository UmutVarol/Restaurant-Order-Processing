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
            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                super.doBegin(transaction, definition);
                Long branch = BranchContext.current();
                if (branch != null && TransactionSynchronizationManager.getResource(entityManagerFactory) instanceof EntityManagerHolder holder) {
                    holder.getEntityManager().unwrap(Session.class).enableFilter(Branch.FILTER).setParameter("branch", branch);
                }
            }
        };
    }
}
