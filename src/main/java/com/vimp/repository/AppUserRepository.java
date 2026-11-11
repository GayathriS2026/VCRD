package com.vimp.repository;

import com.vimp.entity.AppUser;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByUsername(String username);
}
