package com.trader.shared.repository;

import com.trader.shared.entity.ChangelogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChangelogRepository extends JpaRepository<ChangelogEntry, Long> {

    List<ChangelogEntry> findByPublishedTrueOrderByPublishedAtDesc();

    List<ChangelogEntry> findAllByOrderByCreatedAtDesc();
}
