package com.umutvarol.restaurant.security;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    List<AppUser> findByEnabledTrueAndPinHashIsNotNullOrderByDisplayNameAsc();
}
