package com.trader.chatbot.service;

import com.trader.chatbot.config.DiscordBotConfig;
import com.trader.chatbot.event.ChatMessageEvent;
import com.trader.user.entity.UserDiscordBinding;
import com.trader.user.repository.UserDiscordBindingRepository;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Discord Bot 訊息監聽器
 *
 * 三種模式：
 * 1. DM Admin 模式：白名單 Discord ID 直接使用 AI 客服，可查詢任何用戶資料
 * 2. DM 一般用戶模式：需先綁定帳號，只能查詢自己的資料
 * 3. 頻道 @mention 模式：在文字頻道 @Bot 提問，Bot 在頻道中回覆（大家都看得到）
 */
@Slf4j
@Component
public class DiscordBotListener extends ListenerAdapter {

    private final DiscordBotConfig discordBotConfig;
    private final UserDiscordBindingRepository discordBindingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final DiscordBotService discordBotService;

    public DiscordBotListener(DiscordBotConfig discordBotConfig,
                              UserDiscordBindingRepository discordBindingRepository,
                              ApplicationEventPublisher eventPublisher,
                              @org.springframework.context.annotation.Lazy DiscordBotService discordBotService) {
        this.discordBotConfig = discordBotConfig;
        this.discordBindingRepository = discordBindingRepository;
        this.eventPublisher = eventPublisher;
        this.discordBotService = discordBotService;
    }

    private static final String ADMIN_USER_ID = "ADMIN";

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // 忽略 Bot 自身訊息
        if (event.getAuthor().isBot()) return;

        if (event.isFromType(ChannelType.PRIVATE)) {
            handleDmMessage(event);
        } else if (event.isFromType(ChannelType.TEXT)) {
            handleChannelMention(event);
        }
    }

    /**
     * 處理 DM 私訊（原有邏輯）
     */
    private void handleDmMessage(MessageReceivedEvent event) {
        String discordUserId = event.getAuthor().getId();
        String text = event.getMessage().getContentRaw().trim();

        if (text.isEmpty()) return;

        log.debug("收到 Discord DM: discordUserId={} text={}", discordUserId,
                text.length() > 30 ? text.substring(0, 30) + "..." : text);

        // Admin 白名單 → 直接進 AI 客服（可查全部用戶）
        if (discordBotConfig.isAdmin(discordUserId)) {
            event.getChannel().sendMessage("🔍 查詢中...").queue();
            eventPublisher.publishEvent(new ChatMessageEvent(this,
                    ADMIN_USER_ID, "DISCORD", discordUserId, text));
            return;
        }

        // 一般用戶：檢查綁定
        Optional<UserDiscordBinding> binding = discordBindingRepository.findByDiscordUserId(discordUserId);

        if (binding.isPresent() && binding.get().isEnabled()) {
            // 已綁定用戶 → AI 客服
            event.getChannel().sendMessage("正在為您查詢，請稍候... ⏳").queue();
            eventPublisher.publishEvent(new ChatMessageEvent(this,
                    binding.get().getUserId(), "DISCORD", discordUserId, text));
            return;
        }

        // 未綁定 → 檢查是否為連結碼
        String upper = text.toUpperCase();
        if (upper.length() == 8) {
            handleLinkingCode(event, discordUserId, upper);
            return;
        }

        // 非連結碼 → 回覆綁定指引
        event.getChannel().sendMessage(buildBindingGuide()).queue();
    }

    /**
     * 處理頻道 @mention（@Bot 提問，Bot 在頻道中回覆）
     *
     * Admin：直接走 Admin 模式，可查全平台資料
     * 已綁定用戶：走一般用戶模式
     * 未綁定用戶：提示先 DM 綁定
     */
    private void handleChannelMention(MessageReceivedEvent event) {
        // 只處理有 @mention Bot 的訊息
        if (!event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser())) return;

        String discordUserId = event.getAuthor().getId();
        String textChannelId = event.getChannel().getId();

        // 移除 @mention 部分，取得純文字
        String text = event.getMessage().getContentDisplay()
                .replaceAll("@" + event.getJDA().getSelfUser().getName(), "")
                .trim();

        if (text.isEmpty()) {
            event.getMessage().reply("請在 @mention 後面輸入您的問題 😊").queue();
            return;
        }

        log.debug("收到 Discord 頻道 @mention: discordUserId={} channelId={} text={}",
                discordUserId, textChannelId,
                text.length() > 30 ? text.substring(0, 30) + "..." : text);

        // Admin 白名單 → Admin 模式
        if (discordBotConfig.isAdmin(discordUserId)) {
            event.getMessage().reply("🔍 查詢中...").queue();
            eventPublisher.publishEvent(new ChatMessageEvent(this,
                    ADMIN_USER_ID, "DISCORD", discordUserId, text, textChannelId));
            return;
        }

        // 一般用戶：檢查綁定
        Optional<UserDiscordBinding> binding = discordBindingRepository.findByDiscordUserId(discordUserId);

        if (binding.isPresent() && binding.get().isEnabled()) {
            event.getMessage().reply("正在為您查詢，請稍候... ⏳").queue();
            eventPublisher.publishEvent(new ChatMessageEvent(this,
                    binding.get().getUserId(), "DISCORD", discordUserId, text, textChannelId));
            return;
        }

        // 未綁定 → 提示先 DM 綁定
        event.getMessage().reply("請先私訊我綁定帳號，才能使用 AI 客服 🔗\n" +
                "步驟：DM 我 → 輸入 8 位數連結碼").queue();
    }

    private void handleLinkingCode(MessageReceivedEvent event, String discordUserId, String code) {
        Optional<String> boundUserId = discordBotService.bindDiscordAccount(
                discordUserId, event.getAuthor().getName(), code);

        if (boundUserId.isPresent()) {
            event.getChannel().sendMessage("✅ 綁定成功！\n您現在可以直接在這裡詢問任何問題，AI 客服會為您服務。").queue();
        } else {
            event.getChannel().sendMessage("連結碼無效或已過期。\n請重新在網站產生連結碼。").queue();
        }
    }

    private String buildBindingGuide() {
        return "🔗 綁定帳號，使用 AI 客服\n\n" +
                "1️⃣ 註冊 / 登入 → https://hook-fi.com\n" +
                "2️⃣ 前往「設定」→「通知設定」\n" +
                "3️⃣ 點「產生連結碼」\n" +
                "4️⃣ 將 8 位數連結碼貼到這裡\n\n" +
                "完成後即可使用 AI 客服 ✅";
    }
}
