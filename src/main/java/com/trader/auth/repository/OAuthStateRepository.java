package com.trader.auth.repository;

import com.trader.auth.entity.OAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;

public interface OAuthStateRepository extends JpaRepository<OAuthState, String> {

    @Modifying
    @Query("DELETE FROM OAuthState o WHERE o.expiresAt < :now")
    int deleteExpired(LocalDateTime now);
}
