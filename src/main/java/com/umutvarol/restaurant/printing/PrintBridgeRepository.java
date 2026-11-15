package com.umutvarol.restaurant.printing;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrintBridgeRepository extends JpaRepository<PrintBridge, Long> {

    Optional<PrintBridge> findByTokenHash(String tokenHash);

    List<PrintBridge> findByRevokedAtIsNullOrderByIdAsc();
}
