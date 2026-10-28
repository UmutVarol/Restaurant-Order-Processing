package com.umutvarol.restaurant.security;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosDeviceRepository extends JpaRepository<PosDevice, Long> {

    Optional<PosDevice> findByTokenHash(String tokenHash);

    List<PosDevice> findAllByOrderByCreatedAtDesc();
}
