package com.umutvarol.restaurant.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillRepository extends JpaRepository<Bill, Long> {

    @Query("SELECT b FROM Bill b JOIN b.orders o WHERE o.id = :orderId")
    Optional<Bill> findByOrderId(@Param("orderId") Long orderId);

    Optional<Bill> findFirstByTableIdAndStatusOrderByOpenedAtDesc(Long tableId, BillStatus status);

    /** Locks the bill row, so two tills taking money for the same bill at once cannot both overcharge it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Bill b WHERE b.id = :id")
    Optional<Bill> findForUpdate(@Param("id") Long id);

    List<Bill> findByStatus(BillStatus status);
}
