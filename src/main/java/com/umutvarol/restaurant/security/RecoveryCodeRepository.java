package com.umutvarol.restaurant.security;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    List<RecoveryCode> findByUserIdAndUsedAtIsNull(Long userId);

    void deleteByUserId(Long userId);
}
