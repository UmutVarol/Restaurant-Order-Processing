package com.umutvarol.restaurant.inventory;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RecipeLineRepository extends JpaRepository<RecipeLine, Long> {

    List<RecipeLine> findByMenuItemIdOrderByIdAsc(Long menuItemId);

    List<RecipeLine> findByMenuItemIdIn(Collection<Long> menuItemIds);

    void deleteByMenuItemId(Long menuItemId);

    /** Dishes whose own recipe (not an option) uses one of these ingredients. */
    @Query("select distinct r.menuItemId from RecipeLine r where r.optionGroup is null and r.ingredientId in :ingredientIds")
    List<Long> dishesUsing(@Param("ingredientIds") Collection<Long> ingredientIds);

    @Query("select count(r) > 0 from RecipeLine r where r.ingredientId = :id or r.replacesIngredientId = :id")
    boolean isUsed(@Param("id") Long id);
}
