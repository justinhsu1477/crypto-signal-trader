package com.trader.chatbot.service;

import com.trader.chatbot.entity.ChatbotPrompt;
import com.trader.chatbot.repository.ChatbotPromptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChatbotPromptService — DB 管理 + fallback")
class ChatbotPromptServiceTest {

    private ChatbotPromptRepository repo;
    private ChatbotPromptService service;

    @BeforeEach
    void setUp() {
        repo = mock(ChatbotPromptRepository.class);
        service = new ChatbotPromptService(repo);
    }

    private ChatbotPrompt prompt(Long id, String name, int version, String content, boolean active) {
        return ChatbotPrompt.builder()
                .id(id).name(name).version(version).content(content).active(active).build();
    }

    @Nested
    @DisplayName("getActivePrompt")
    class GetActivePromptTests {

        @Test
        @DisplayName("DB 有 active → 回 DB 內容")
        void returnsDbActive() {
            when(repo.findFirstByNameAndActiveTrue("system_user"))
                    .thenReturn(Optional.of(prompt(1L, "system_user", 2, "DB prompt", true)));

            String result = service.getActivePrompt("system_user", "fallback");

            assertThat(result).isEqualTo("DB prompt");
        }

        @Test
        @DisplayName("DB 無資料 → 回 fallback")
        void returnsFallbackWhenEmpty() {
            when(repo.findFirstByNameAndActiveTrue("system_user")).thenReturn(Optional.empty());

            String result = service.getActivePrompt("system_user", "code default");

            assertThat(result).isEqualTo("code default");
        }

        @Test
        @DisplayName("DB 查詢異常 → 回 fallback（graceful degrade）")
        void fallbackOnDbException() {
            when(repo.findFirstByNameAndActiveTrue(any())).thenThrow(new RuntimeException("DB down"));

            String result = service.getActivePrompt("system_user", "code default");

            assertThat(result).isEqualTo("code default");
        }

        @Test
        @DisplayName("Cache — 連續呼叫只打 DB 一次")
        void cachesResult() {
            when(repo.findFirstByNameAndActiveTrue("system_user"))
                    .thenReturn(Optional.of(prompt(1L, "system_user", 1, "cached", true)));

            service.getActivePrompt("system_user", "x");
            service.getActivePrompt("system_user", "x");
            service.getActivePrompt("system_user", "x");

            verify(repo, times(1)).findFirstByNameAndActiveTrue("system_user");
        }

        @Test
        @DisplayName("invalidateCache 後重新讀 DB")
        void invalidateClearsCache() {
            when(repo.findFirstByNameAndActiveTrue("system_user"))
                    .thenReturn(Optional.of(prompt(1L, "system_user", 1, "v1", true)));

            service.getActivePrompt("system_user", "x");
            service.invalidateCache();
            service.getActivePrompt("system_user", "x");

            verify(repo, times(2)).findFirstByNameAndActiveTrue("system_user");
        }
    }

    @Nested
    @DisplayName("seedIfAbsent")
    class SeedIfAbsentTests {

        @Test
        @DisplayName("name 不存在 → 新增 v1 active")
        void seedsWhenMissing() {
            when(repo.existsByName("system_user")).thenReturn(false);
            when(repo.save(any(ChatbotPrompt.class))).thenAnswer(inv -> inv.getArgument(0));

            service.seedIfAbsent("system_user", "default content", "首次 seed");

            verify(repo).save(any(ChatbotPrompt.class));
        }

        @Test
        @DisplayName("name 已存在 → 不新增")
        void skipsWhenExists() {
            when(repo.existsByName("system_user")).thenReturn(true);

            service.seedIfAbsent("system_user", "default content", "首次 seed");

            verify(repo, never()).save(any(ChatbotPrompt.class));
        }
    }

    @Nested
    @DisplayName("createVersion / activate")
    class AdminOpsTests {

        @Test
        @DisplayName("第一版建立 → version=1, active=false")
        void firstVersion() {
            when(repo.findByNameOrderByVersionDesc("p1")).thenReturn(List.of());
            when(repo.save(any(ChatbotPrompt.class))).thenAnswer(inv -> inv.getArgument(0));

            ChatbotPrompt created = service.createVersion("p1", "content", "desc");

            assertThat(created.getVersion()).isEqualTo(1);
            assertThat(created.isActive()).isFalse();
        }

        @Test
        @DisplayName("已有 v3 → 建立 v4")
        void nextVersion() {
            when(repo.findByNameOrderByVersionDesc("p1"))
                    .thenReturn(List.of(prompt(3L, "p1", 3, "c3", true)));
            when(repo.save(any(ChatbotPrompt.class))).thenAnswer(inv -> inv.getArgument(0));

            ChatbotPrompt created = service.createVersion("p1", "content", "desc");

            assertThat(created.getVersion()).isEqualTo(4);
        }

        @Test
        @DisplayName("activate — 舊 active deactivate, 新 active activate")
        void activateSwitches() {
            ChatbotPrompt old = prompt(1L, "p1", 1, "v1", true);
            ChatbotPrompt newV = prompt(2L, "p1", 2, "v2", false);
            when(repo.findById(2L)).thenReturn(Optional.of(newV));
            when(repo.findByNameOrderByVersionDesc("p1")).thenReturn(List.of(newV, old));
            when(repo.save(any(ChatbotPrompt.class))).thenAnswer(inv -> inv.getArgument(0));

            service.activate(2L);

            assertThat(newV.isActive()).isTrue();
            assertThat(old.isActive()).isFalse();
        }

        @Test
        @DisplayName("activate 不存在的 id → 拋異常")
        void activateNonExistent() {
            when(repo.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.activate(99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }
    }
}
