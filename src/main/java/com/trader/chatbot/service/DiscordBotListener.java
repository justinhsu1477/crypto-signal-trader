package com.trader.chatbot.service;

import com.trader.chatbot.event.ChatMessageEvent;
import com.trader.user.entity.LineLinkingCode;
import com.trader.user.entity.UserDiscordBinding;
import com.trader.user.repository.LineLinkingCodeRepository;
import com.trader.user.repository.UserDiscordBindingRepository;
import lombok.RequiredArgsConstructor;
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
 * 處理 DM（私人訊息）：
 * - 已綁定用戶 → 發布 ChatMessageEvent → AI 客服回覆
 * - 未綁定 + 8 碼連結碼 → 嘗試綁定
 * - 未綁定 + 其他 → 回覆綁定指引
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordBotListener extends ListenerAdapter {

    private final UserDiscordBindingRepository discordBindingRepository;
    private final LineLinkingCodeRepository linkingCodeRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // 忽略 Bot 自身訊息
        if (event.getAuthor().isBot()) return;

        // 只處理 DM（私人頻道）
        if (!event.isFromType(ChannelType.PRIVATE)) return;

        String discordUserId = event.getAuthor().getId();
        String displayName = event.getAuthor().getName();
        String text = event.getMessage().getContentRaw().trim();

        if (text.isEmpty()) return;

        log.debug("收到 Discord DM: discordUserId={} text={}", discordUserId,
                text.length() > 30 ? text.substring(0, 30) + "..." : text);

        // 檢查綁定
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
            Optional<LineLinkingCode> code = linkingCodeRepository.findByCodeAndUsedFalse(upper);
            if (code.isPresent() && !code.get().isExpired()) {
                // 標記碼已使用
                LineLinkingCode linkCode = code.get();
                linkCode.setUsed(true);
                linkingCodeRepository.save(linkCode);

                // 建立 Discord 綁定
                UserDiscordBinding newBinding = UserDiscordBinding.builder()
                        .userId(linkCode.getUserId())
                        .discordUserId(discordUserId)
                        .displayName(displayName)
                        .enabled(true)
                        .build();
                discordBindingRepository.save(newBinding);

                log.info("Discord 綁定成功: userId={} discordUserId={}", linkCode.getUserId(), discordUserId);
                event.getChannel().sendMessage("✅ 綁定成功！\n您現在可以直接在這裡詢問任何問題，AI 客服會為您服務。").queue();
                return;
            } else {
                event.getChannel().sendMessage("連結碼無效或已過期。\n請重新在網站產生連結碼。").queue();
                return;
            }
        }

        // 非連結碼 → 回覆綁定指引
        event.getChannel().sendMessage(buildBindingGuide()).queue();
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
