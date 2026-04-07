package com.trader.user.repository;

import com.trader.user.entity.UserDiscordBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserDiscordBindingRepository extends JpaRepository<UserDiscordBinding, String> {

    Optional<UserDiscordBinding> findByDiscordUserId(String discordUserId);
}
