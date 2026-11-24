package com.umutvarol.restaurant.repricing;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface PricingSettingsRepository extends JpaRepository<PricingSettings, Long> {

    /** The current business's row; Hibernate's tenant filter leaves only its own. */
    Optional<PricingSettings> findFirstByOrderByIdAsc();
}
