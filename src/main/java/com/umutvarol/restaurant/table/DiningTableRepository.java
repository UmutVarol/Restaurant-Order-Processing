package com.umutvarol.restaurant.table;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiningTableRepository extends JpaRepository<DiningTable, Long> {

    List<DiningTable> findAllByOrderByIdAsc();

    /** The tables on the floor; removed ones keep their history but leave every list. */
    List<DiningTable> findByActiveTrueOrderByIdAsc();

    Optional<DiningTable> findByQrTokenHash(String qrTokenHash);

    Optional<DiningTable> findByCode(String code);

    /** Holds the table's row until the transaction ends, so two hosts cannot give it to two parties at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DiningTable t where t.id = :id")
    Optional<DiningTable> lockById(@Param("id") Long id);
}
