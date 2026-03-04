package com.trader.auth.repository;

import com.trader.auth.entity.OAuthProviderType;
import com.trader.auth.entity.UserOAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserOAuthProviderRepository extends JpaRepository<UserOAuthProvider, Long> {

    Optional<UserOAuthProvider> findByProviderAndProviderUserId(
            OAuthProviderType provider, String providerUserId);

    List<UserOAuthProvider> findByUserId(String userId);

    Optional<UserOAuthProvider> findByUserIdAndProvider(String userId, OAuthProviderType provider);

    void deleteByUserId(String userId);
}
