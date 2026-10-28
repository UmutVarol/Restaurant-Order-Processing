package com.umutvarol.restaurant.inventory;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    List<Ingredient> findByArchivedFalseOrderByNameAsc();

    boolean existsByNameIgnoreCaseAndArchivedFalseAndIdNot(String name, Long id);

    /** Holds the ingredient while a count works out its difference, so two counts cannot both apply it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Ingredient i where i.id = :id")
    Optional<Ingredient> findForUpdate(@Param("id") Long id);
}
