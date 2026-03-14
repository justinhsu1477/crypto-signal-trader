package com.trader.shared.controller;

import com.trader.shared.config.AppConstants;
import com.trader.shared.entity.ChangelogEntry;
import com.trader.shared.repository.ChangelogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChangelogController {

    private final ChangelogRepository changelogRepository;

    // ==================== 公開端點（所有已登入用戶） ====================

    /**
     * 查詢已發佈的更新日誌
     * GET /api/changelog
     */
    @GetMapping("/api/changelog")
    public ResponseEntity<List<ChangelogEntry>> getPublishedChangelogs() {
        return ResponseEntity.ok(changelogRepository.findByPublishedTrueOrderByPublishedAtDesc());
    }

    // ==================== Admin 端點 ====================

    /**
     * 查詢所有更新日誌（含未發佈）
     * GET /api/admin/changelog
     */
    @GetMapping("/api/admin/changelog")
    public ResponseEntity<List<ChangelogEntry>> getAllChangelogs() {
        return ResponseEntity.ok(changelogRepository.findAllByOrderByCreatedAtDesc());
    }

    /**
     * 新增更新日誌
     * POST /api/admin/changelog
     */
    @PostMapping("/api/admin/changelog")
    public ResponseEntity<ChangelogEntry> createChangelog(@RequestBody ChangelogEntry entry) {
        entry.setId(null);
        ChangelogEntry saved = changelogRepository.save(entry);
        log.info("新增 Changelog: version={}, title={}", saved.getVersion(), saved.getTitle());
        return ResponseEntity.ok(saved);
    }

    /**
     * 更新日誌
     * PUT /api/admin/changelog/{id}
     */
    @PutMapping("/api/admin/changelog/{id}")
    public ResponseEntity<ChangelogEntry> updateChangelog(
            @PathVariable Long id, @RequestBody ChangelogEntry update) {
        return changelogRepository.findById(id)
                .map(entry -> {
                    if (update.getVersion() != null) entry.setVersion(update.getVersion());
                    if (update.getTitle() != null) entry.setTitle(update.getTitle());
                    if (update.getContent() != null) entry.setContent(update.getContent());
                    if (update.getCategory() != null) entry.setCategory(update.getCategory());
                    return ResponseEntity.ok(changelogRepository.save(entry));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 發佈更新日誌
     * POST /api/admin/changelog/{id}/publish
     */
    @PostMapping("/api/admin/changelog/{id}/publish")
    public ResponseEntity<ChangelogEntry> publishChangelog(@PathVariable Long id) {
        return changelogRepository.findById(id)
                .map(entry -> {
                    entry.setPublished(true);
                    entry.setPublishedAt(LocalDateTime.now(AppConstants.ZONE_ID));
                    log.info("發佈 Changelog: version={}", entry.getVersion());
                    return ResponseEntity.ok(changelogRepository.save(entry));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 刪除更新日誌
     * DELETE /api/admin/changelog/{id}
     */
    @DeleteMapping("/api/admin/changelog/{id}")
    public ResponseEntity<Map<String, String>> deleteChangelog(@PathVariable Long id) {
        if (!changelogRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        changelogRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已刪除"));
    }
}
