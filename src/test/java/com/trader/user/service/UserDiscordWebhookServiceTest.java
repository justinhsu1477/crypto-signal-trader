package com.trader.user.service;

import com.trader.user.entity.UserDiscordWebhook;
import com.trader.user.repository.UserDiscordWebhookRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserDiscordWebhookService 單元測試
 *
 * 重點覆蓋：IDOR 防護（所有權驗證）
 */
@ExtendWith(MockitoExtension.class)
class UserDiscordWebhookServiceTest {

    @Mock
    private UserDiscordWebhookRepository webhookRepository;

    @InjectMocks
    private UserDiscordWebhookService service;

    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";

    // ==================== createOrUpdateWebhook ====================

    @Nested
    @DisplayName("createOrUpdateWebhook — 建立/更新")
    class CreateOrUpdate {

        @Test
        @DisplayName("無舊 webhook — 建立新 webhook")
        void createsNewWhenNoneExists() {
            when(webhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc(USER_A))
                    .thenReturn(Optional.empty());
            when(webhookRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UserDiscordWebhook result = service.createOrUpdateWebhook(
                    USER_A, "https://discord.com/api/webhooks/123/abc", "My Hook");

            assertThat(result.getUserId()).isEqualTo(USER_A);
            assertThat(result.getWebhookUrl()).isEqualTo("https://discord.com/api/webhooks/123/abc");
            assertThat(result.getName()).isEqualTo("My Hook");
            assertThat(result.isEnabled()).isTrue();
            assertThat(result.getWebhookId()).isNotNull();

            // 只 save 一次（新 webhook），不停用舊的
            verify(webhookRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("有舊 webhook — 停用舊的，建立新的")
        void disablesOldAndCreatesNew() {
            UserDiscordWebhook old = UserDiscordWebhook.builder()
                    .webhookId("old-wh").userId(USER_A).enabled(true).build();
            when(webhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc(USER_A))
                    .thenReturn(Optional.of(old));
            when(webhookRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UserDiscordWebhook result = service.createOrUpdateWebhook(
                    USER_A, "https://discord.com/api/webhooks/456/def", null);

            // 舊 webhook 被停用
            assertThat(old.isEnabled()).isFalse();
            // save 兩次：停用舊 + 儲存新
            verify(webhookRepository, times(2)).save(any());
            // 新 webhook
            assertThat(result.isEnabled()).isTrue();
            assertThat(result.getName()).isEqualTo("Discord Webhook"); // 預設名稱
        }
    }

    // ==================== 查詢方法 ====================

    @Nested
    @DisplayName("查詢方法")
    class QueryMethods {

        @Test
        @DisplayName("getPrimaryWebhook — 委派 repository")
        void getPrimaryWebhook() {
            UserDiscordWebhook wh = UserDiscordWebhook.builder()
                    .webhookId("wh-1").userId(USER_A).enabled(true).build();
            when(webhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc(USER_A))
                    .thenReturn(Optional.of(wh));

            Optional<UserDiscordWebhook> result = service.getPrimaryWebhook(USER_A);

            assertThat(result).isPresent();
            assertThat(result.get().getWebhookId()).isEqualTo("wh-1");
        }

        @Test
        @DisplayName("getEnabledWebhooks — 委派 repository")
        void getEnabledWebhooks() {
            when(webhookRepository.findByUserIdAndEnabledTrue(USER_A)).thenReturn(List.of());

            List<UserDiscordWebhook> result = service.getEnabledWebhooks(USER_A);

            assertThat(result).isEmpty();
            verify(webhookRepository).findByUserIdAndEnabledTrue(USER_A);
        }

        @Test
        @DisplayName("getAllWebhooks — 委派 repository")
        void getAllWebhooks() {
            when(webhookRepository.findByUserId(USER_A)).thenReturn(List.of());

            List<UserDiscordWebhook> result = service.getAllWebhooks(USER_A);

            assertThat(result).isEmpty();
            verify(webhookRepository).findByUserId(USER_A);
        }
    }

    // ==================== disableWebhook — IDOR 防護 ====================

    @Nested
    @DisplayName("disableWebhook — 所有權驗證")
    class DisableWebhook {

        @Test
        @DisplayName("擁有者停用 — 成功")
        void ownerCanDisable() {
            UserDiscordWebhook wh = UserDiscordWebhook.builder()
                    .webhookId("wh-1").userId(USER_A).enabled(true).build();
            when(webhookRepository.findByWebhookIdAndUserId("wh-1", USER_A))
                    .thenReturn(Optional.of(wh));

            service.disableWebhook(USER_A, "wh-1");

            assertThat(wh.isEnabled()).isFalse();
            verify(webhookRepository).save(wh);
        }

        @Test
        @DisplayName("非擁有者停用 — 拋 IllegalArgumentException（IDOR 防護）")
        void nonOwnerCannotDisable() {
            // User B 的 webhook，User A 不應該能停用
            when(webhookRepository.findByWebhookIdAndUserId("wh-b", USER_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.disableWebhook(USER_A, "wh-b"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("無權操作");

            verify(webhookRepository, never()).save(any());
        }

        @Test
        @DisplayName("webhook 不存在 — 拋 IllegalArgumentException")
        void nonExistentWebhook() {
            when(webhookRepository.findByWebhookIdAndUserId("non-existent", USER_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.disableWebhook(USER_A, "non-existent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不存在");

            verify(webhookRepository, never()).save(any());
        }
    }

    // ==================== deleteWebhook — IDOR 防護 ====================

    @Nested
    @DisplayName("deleteWebhook — 所有權驗證")
    class DeleteWebhook {

        @Test
        @DisplayName("擁有者刪除 — 成功")
        void ownerCanDelete() {
            UserDiscordWebhook wh = UserDiscordWebhook.builder()
                    .webhookId("wh-1").userId(USER_A).enabled(true).build();
            when(webhookRepository.findByWebhookIdAndUserId("wh-1", USER_A))
                    .thenReturn(Optional.of(wh));

            service.deleteWebhook(USER_A, "wh-1");

            verify(webhookRepository).delete(wh);
        }

        @Test
        @DisplayName("非擁有者刪除 — 拋 IllegalArgumentException（IDOR 防護）")
        void nonOwnerCannotDelete() {
            when(webhookRepository.findByWebhookIdAndUserId("wh-b", USER_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteWebhook(USER_A, "wh-b"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("無權操作");

            verify(webhookRepository, never()).delete(any(UserDiscordWebhook.class));
            verify(webhookRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("webhook 不存在 — 拋 IllegalArgumentException")
        void nonExistentWebhook() {
            when(webhookRepository.findByWebhookIdAndUserId("non-existent", USER_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteWebhook(USER_A, "non-existent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不存在");
        }
    }

    // ==================== 跨用戶隔離情境 ====================

    @Nested
    @DisplayName("跨用戶隔離 — 完整 IDOR 場景")
    class CrossUserIsolation {

        @Test
        @DisplayName("User A 無法停用 User B 的 webhook")
        void userACannotDisableUserBWebhook() {
            // User B 擁有 wh-b
            UserDiscordWebhook whB = UserDiscordWebhook.builder()
                    .webhookId("wh-b").userId(USER_B).enabled(true).build();

            // findByWebhookIdAndUserId("wh-b", USER_A) → empty（User A 不是擁有者）
            when(webhookRepository.findByWebhookIdAndUserId("wh-b", USER_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.disableWebhook(USER_A, "wh-b"))
                    .isInstanceOf(IllegalArgumentException.class);

            // 確保 User B 的 webhook 沒被動到
            assertThat(whB.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("User A 無法刪除 User B 的 webhook")
        void userACannotDeleteUserBWebhook() {
            when(webhookRepository.findByWebhookIdAndUserId("wh-b", USER_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteWebhook(USER_A, "wh-b"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(webhookRepository, never()).delete(any(UserDiscordWebhook.class));
        }

        @Test
        @DisplayName("User B 可以操作自己的 webhook")
        void userBCanOperateOwnWebhook() {
            UserDiscordWebhook whB = UserDiscordWebhook.builder()
                    .webhookId("wh-b").userId(USER_B).enabled(true).build();
            when(webhookRepository.findByWebhookIdAndUserId("wh-b", USER_B))
                    .thenReturn(Optional.of(whB));

            // 停用成功
            service.disableWebhook(USER_B, "wh-b");
            assertThat(whB.isEnabled()).isFalse();
            verify(webhookRepository).save(whB);
        }
    }
}
