package com.trader.user.repository;

import com.trader.user.entity.UserLineBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserLineBindingRepository extends JpaRepository<UserLineBinding, String> {

    Optional<UserLineBinding> findByLineUserId(String lineUserId);

    Optional<UserLineBinding> findByUserIdAndEnabledTrue(String userId);

    @Query("SELECT DISTINCT b.userId FROM UserLineBinding b WHERE b.enabled = true")
    List<String> findUserIdsWithEnabledBinding();
}
