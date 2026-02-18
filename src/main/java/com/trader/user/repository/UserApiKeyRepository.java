package com.trader.user.repository;

import com.trader.user.entity.UserApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserApiKeyRepository extends JpaRepository<UserApiKey, Long> {

    List<UserApiKey> findByUserId(String userId);

    Optional<UserApiKey> findByUserIdAndExchange(String userId, String exchange);
}
