package com.trader.trading.service;

import com.trader.trading.entity.PromptVersion;
import com.trader.trading.repository.PromptVersionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Prompt 版本管理服務。
 *
 * <p><strong>Append-only invariant</strong>：{@code prompt_versions} 是整套 prompt audit chain
 * 的根節點。每個 {@code signals.custom_prompt_version} 跟 {@code admin_audit_log.before_hash /
 * after_hash} 都依賴此表的 id 能查回當時的 prompt 全文。**禁止實作 DELETE 或物理覆寫**：
 *
 * <ul>
 *     <li>本 service 只開 {@code createVersion / activateVersion / getActivePrompt / getAllVersions}</li>
 *     <li>{@link com.trader.dashboard.controller.AdminPromptController} 對應只開 GET / POST，
 *         由 {@code AdminPromptControllerImmutabilityTest} reflection 護欄擋住未來誤加 DELETE</li>
 *     <li>需要「淘汰」舊版本 → 改用 {@code active=false} 軟去活，不可實刪</li>
 * </ul>
 *
 * <p>違反此 invariant 會導致歷史 broadcast 對應的 prompt 全文無法回溯，影響法務 / 糾紛場景。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptVersionService {

    private final PromptVersionRepository promptVersionRepository;
    private final MonitorConfigStore monitorConfigStore;

    /**
     * 建立新版本（自動遞增版本號）
     */
    @Transactional
    public PromptVersion createVersion(String content, String description) {
        int nextVersion = promptVersionRepository.findMaxVersion() + 1;

        PromptVersion version = PromptVersion.builder()
                .version(nextVersion)
                .content(content)
                .description(description)
                .active(false)
                .tokenCount(estimateTokenCount(content))
                .build();

        PromptVersion saved = promptVersionRepository.save(version);
        log.info("建立 Prompt 版本: v{} ({})", saved.getVersion(), description);
        return saved;
    }

    /**
     * 啟用指定版本（@Transactional 確保 deactivateAll + activate 原子性）
     * 啟用後觸發 gRPC 推送
     */
    @Transactional
    public PromptVersion activateVersion(Long id) {
        PromptVersion target = promptVersionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Prompt 版本不存在: id=" + id));

        promptVersionRepository.deactivateAll();
        target.setActive(true);
        PromptVersion saved = promptVersionRepository.save(target);

        syncPromptToMonitor(saved);
        log.info("啟用 Prompt 版本: v{} ({})", saved.getVersion(), saved.getDescription());
        return saved;
    }

    /**
     * 取得當前生效的 prompt
     */
    public Optional<PromptVersion> getActivePrompt() {
        return promptVersionRepository.findByActiveTrue();
    }

    /**
     * 列出所有版本（版本號倒序）
     */
    public List<PromptVersion> getAllVersions() {
        return promptVersionRepository.findAllByOrderByVersionDesc();
    }

    /**
     * 透過 gRPC 推送 prompt 到 Python Monitor
     */
    public void syncPromptToMonitor(PromptVersion version) {
        monitorConfigStore.updatePrompt(version.getContent(), version.getVersion());
    }

    /**
     * 啟動時同步：如果 DB 有 active prompt，推送到 MonitorConfigStore
     */
    @PostConstruct
    void syncOnStartup() {
        promptVersionRepository.findByActiveTrue().ifPresent(v -> {
            monitorConfigStore.updatePrompt(v.getContent(), v.getVersion());
            log.info("啟動同步: 推送 active prompt v{} 到 MonitorConfigStore", v.getVersion());
        });
    }

    /**
     * 粗估 token 數（中文 ~1.5 char/token, 英文 ~4 char/token）
     */
    private int estimateTokenCount(String content) {
        if (content == null) return 0;
        int chars = content.length();
        // 混合中英文的 prompt，取中間值 ~2.5 char/token
        return chars / 3 + 1;
    }
}
