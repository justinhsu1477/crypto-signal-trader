package com.trader.chatbot.service;

import com.trader.chatbot.config.DiscordBotConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Discord Bot 生命週期管理 + 訊息回覆
 *
 * - @PostConstruct：啟動 JDA（若 enabled + token 有效）
 * - sendDmReply()：供 ChatbotConsumer 回覆 Discord 用戶（私訊）
 * - sendChannelReply()：供 ChatbotConsumer 回覆 Discord 頻道訊息
 * - DisposableBean：graceful shutdown
 */
@Slf4j
@Service
public class DiscordBotService implements DisposableBean {

    private final DiscordBotConfig config;
    private final DiscordBotListener listener;
    private JDA jda;

    public DiscordBotService(DiscordBotConfig config, DiscordBotListener listener) {
        this.config = config;
        this.listener = listener;
    }

    @PostConstruct
    public void start() {
        if (!config.isEnabled() || config.getToken().isBlank()) {
            log.info("Discord Bot 未啟用");
            return;
        }
        try {
            jda = JDABuilder.createDefault(config.getToken())
                    .enableIntents(GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .disableIntents(GatewayIntent.GUILD_PRESENCES, GatewayIntent.GUILD_MESSAGE_TYPING)
                    .disableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                    .addEventListeners(listener)
                    .setActivity(Activity.watching("HookFi AI 客服"))
                    .build();
            log.info("Discord Bot 已啟動");
        } catch (Exception e) {
            log.error("Discord Bot 啟動失敗: {}", e.getMessage(), e);
        }
    }

    /**
     * 透過 DM 回覆 Discord 用戶
     *
     * @param discordUserId Discord 用戶 ID
     * @param text          回覆文字
     */
    public void sendDmReply(String discordUserId, String text) {
        if (jda == null) return;
        jda.retrieveUserById(discordUserId).queue(user -> {
            user.openPrivateChannel().queue(channel -> {
                // Discord message limit is 2000 chars
                if (text.length() > 2000) {
                    channel.sendMessage(text.substring(0, 1997) + "...").queue();
                } else {
                    channel.sendMessage(text).queue();
                }
            }, error -> log.warn("無法開啟 DM: discordUserId={} error={}", discordUserId, error.getMessage()));
        }, error -> log.warn("找不到 Discord 用戶: discordUserId={} error={}", discordUserId, error.getMessage()));
    }

    /**
     * 在 Discord 頻道回覆訊息
     *
     * @param textChannelId Discord 文字頻道 ID
     * @param text          回覆文字
     */
    public void sendChannelReply(String textChannelId, String text) {
        if (jda == null) return;
        var channel = jda.getTextChannelById(textChannelId);
        if (channel == null) {
            log.warn("找不到 Discord 頻道: channelId={}", textChannelId);
            return;
        }
        String msg = text.length() > 2000 ? text.substring(0, 1997) + "..." : text;
        channel.sendMessage(msg).queue(
                success -> {},
                error -> log.warn("Discord 頻道回覆失敗: channelId={} error={}", textChannelId, error.getMessage())
        );
    }

    @Override
    public void destroy() {
        if (jda != null) {
            log.info("Discord Bot 正在關閉...");
            jda.shutdown();
        }
    }
}
