package com.trader.chatbot.service;

import com.trader.chatbot.config.DiscordBotConfig;
import com.trader.chatbot.entity.ChatConversation;
import com.trader.chatbot.repository.ChatConversationRepository;
import com.trader.user.entity.LineLinkingCode;
import com.trader.user.entity.UserDiscordBinding;
import com.trader.user.repository.LineLinkingCodeRepository;
import com.trader.user.repository.UserDiscordBindingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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
    private final LineLinkingCodeRepository linkingCodeRepository;
    private final UserDiscordBindingRepository discordBindingRepository;
    private final ChatConversationRepository conversationRepository;
    private JDA jda;

    public DiscordBotService(DiscordBotConfig config, DiscordBotListener listener,
                             LineLinkingCodeRepository linkingCodeRepository,
                             UserDiscordBindingRepository discordBindingRepository,
                             ChatConversationRepository conversationRepository) {
        this.config = config;
        this.listener = listener;
        this.linkingCodeRepository = linkingCodeRepository;
        this.discordBindingRepository = discordBindingRepository;
        this.conversationRepository = conversationRepository;
    }

    @PostConstruct
    public void start() {
        if (!config.isEnabled() || config.getToken().isBlank()) {
            log.info("Discord Bot 未啟用");
            return;
        }
        try {
            jda = JDABuilder.createDefault(config.getToken())
                    .enableIntents(GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT,
                            GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.DIRECT_MESSAGE_REACTIONS)
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
     * 透過 DM 回覆 Discord 用戶（+ 自動加 👍👎 反應供 feedback）
     *
     * @param discordUserId  Discord 用戶 ID
     * @param text           回覆文字
     * @param conversationId AI 回覆的 ChatConversation ID（用於 feedback 追蹤，可為 null）
     */
    public void sendDmReply(String discordUserId, String text, Long conversationId) {
        if (jda == null) return;
        jda.retrieveUserById(discordUserId).queue(user -> {
            user.openPrivateChannel().queue(channel -> {
                String msg = text.length() > 2000 ? text.substring(0, 1997) + "..." : text;
                channel.sendMessage(msg).queue(
                        message -> addFeedbackReactions(message, conversationId),
                        error -> log.warn("DM 送出失敗: discordUserId={} error={}", discordUserId, error.getMessage())
                );
            }, error -> log.warn("無法開啟 DM: discordUserId={} error={}", discordUserId, error.getMessage()));
        }, error -> log.warn("找不到 Discord 用戶: discordUserId={} error={}", discordUserId, error.getMessage()));
    }

    /**
     * 在 Discord 頻道回覆訊息（@mention 提問者 + 👍👎 反應）
     *
     * @param textChannelId  Discord 文字頻道 ID
     * @param discordUserId  提問者的 Discord ID（用於 @mention）
     * @param text           回覆文字
     * @param conversationId AI 回覆的 ChatConversation ID（用於 feedback 追蹤，可為 null）
     */
    public void sendChannelReply(String textChannelId, String discordUserId, String text, Long conversationId) {
        if (jda == null) return;
        var channel = jda.getTextChannelById(textChannelId);
        if (channel == null) {
            log.warn("找不到 Discord 頻道: channelId={}", textChannelId);
            return;
        }
        String mention = "<@" + discordUserId + "> ";
        String fullMsg = mention + text;
        String msg = fullMsg.length() > 2000 ? fullMsg.substring(0, 1997) + "..." : fullMsg;
        channel.sendMessage(msg).queue(
                message -> addFeedbackReactions(message, conversationId),
                error -> log.warn("Discord 頻道回覆失敗: channelId={} error={}", textChannelId, error.getMessage())
        );
    }

    /**
     * Bot 回覆送出後自動加 👍👎 反應 + 記錄 Discord message ID
     */
    private void addFeedbackReactions(net.dv8tion.jda.api.entities.Message message, Long conversationId) {
        try {
            message.addReaction(Emoji.fromUnicode("👍")).queue();
            message.addReaction(Emoji.fromUnicode("👎")).queue();

            // 記錄 Discord message ID → ChatConversation（用於反應事件反查）
            if (conversationId != null) {
                conversationRepository.findById(conversationId).ifPresent(conv -> {
                    conv.setDiscordMessageId(message.getId());
                    conversationRepository.save(conv);
                });
            }
        } catch (Exception e) {
            log.debug("加入 feedback 反應失敗: messageId={} error={}", message.getId(), e.getMessage());
        }
    }

    /**
     * 處理 Discord 帳號綁定（事務保護，避免 race condition 重複綁定）
     *
     * @return 綁定成功的 userId，失敗回傳 empty
     */
    @Transactional
    public Optional<String> bindDiscordAccount(String discordUserId, String displayName, String code) {
        Optional<LineLinkingCode> codeEntity = linkingCodeRepository.findByCodeAndUsedFalse(code);
        if (codeEntity.isEmpty() || codeEntity.get().isExpired()) {
            return Optional.empty();
        }

        LineLinkingCode linkCode = codeEntity.get();
        linkCode.setUsed(true);
        linkingCodeRepository.save(linkCode);

        UserDiscordBinding newBinding = UserDiscordBinding.builder()
                .userId(linkCode.getUserId())
                .discordUserId(discordUserId)
                .displayName(displayName)
                .enabled(true)
                .build();
        discordBindingRepository.save(newBinding);

        log.info("Discord 綁定成功: userId={} discordUserId={}", linkCode.getUserId(), discordUserId);
        return Optional.of(linkCode.getUserId());
    }

    @Override
    public void destroy() {
        if (jda != null) {
            log.info("Discord Bot 正在關閉...");
            jda.shutdown();
        }
    }
}
