package com.trader.shared.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Mirror webhook 全域開關。
 *
 * <p>三層 kill switch：
 * <ol>
 *     <li>{@link #isEnabled()} 全域 — 出事時 yml / env var 改 false 一鍵全停</li>
 *     <li>per-source {@code signal_sources.mirror_enabled} — 單一源關掉</li>
 *     <li>{@code signal_sources.mirror_webhook_url} 為 null/blank — 視為未設定</li>
 * </ol>
 *
 * <p>三層都通過才會真的發送。預設 false → 不會有任何 outbound webhook 流量。
 */
@Getter
@ConfigurationProperties(prefix = "mirror")
public class MirrorConfig {

    private final boolean enabled;

    public MirrorConfig(@DefaultValue("false") boolean enabled) {
        this.enabled = enabled;
    }
}
