package com.trader.user.repository;

import com.trader.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByRole(User.Role role);

    long countByRole(User.Role role);

    List<User> findByRole(User.Role role);

    // ── Funnel Stats ──

    long countByEmailVerifiedTrue();

    List<User> findTop10ByOrderByCreatedAtDesc();

    List<User> findByNameContainingIgnoreCase(String name);

    @Query("SELECT CAST(u.createdAt AS date) AS d, COUNT(u) FROM User u " +
            "WHERE u.createdAt >= :since GROUP BY CAST(u.createdAt AS date) ORDER BY d")
    List<Object[]> countRegistrationsByDate(@Param("since") LocalDateTime since);
}
