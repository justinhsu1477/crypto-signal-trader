package com.trader.trading.service;

import com.trader.trading.grpc.generated.ConfigUpdate;
import com.trader.trading.grpc.generated.MonitorConfig;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MonitorConfigStore 單元測試
 *
 * 覆蓋：初始化、更新設定、Observer 管理、推送邏輯、dead observer 清理
 */
class MonitorConfigStoreTest {

    private MonitorConfigStore store;

    @BeforeEach
    void setUp() {
        store = new MonitorConfigStore("");
    }

    // ==================== initFromDefaults ====================

    @Nested
    @DisplayName("initFromDefaults — 預設頻道載入")
    class InitTests {

        @Test
        @DisplayName("空字串 — config 為空")
        void emptyDefault() {
            store.initFromDefaults();
            MonitorConfig config = store.getCurrentConfig();
            assertThat(config.getChannelIdsList()).isEmpty();
        }

        @Test
        @DisplayName("有值 — 載入頻道清單")
        void withDefaults() {
            var storeWithDefaults = new MonitorConfigStore("ch1, ch2, ch3");
            storeWithDefaults.initFromDefaults();

            MonitorConfig config = storeWithDefaults.getCurrentConfig();
            assertThat(config.getChannelIdsList()).containsExactly("ch1", "ch2", "ch3");
            assertThat(config.getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("空白和空項目被過濾")
        void filtersBlanks() {
            var storeWithBlanks = new MonitorConfigStore("ch1, , ch2, ");
            storeWithBlanks.initFromDefaults();

            assertThat(storeWithBlanks.getCurrentConfig().getChannelIdsList())
                    .containsExactly("ch1", "ch2");
        }
    }

    // ==================== updateConfig ====================

    @Nested
    @DisplayName("updateConfig — 更新設定")
    class UpdateTests {

        @Test
        @DisplayName("更新 channelIds")
        void updateChannels() {
            store.updateConfig(
                    List.of("ch-new-1", "ch-new-2"),
                    null, null, null,
                    "admin", "test_update"
            );

            MonitorConfig config = store.getCurrentConfig();
            assertThat(config.getChannelIdsList()).containsExactly("ch-new-1", "ch-new-2");
            assertThat(config.getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("版本號遞增")
        void versionIncrements() {
            store.updateConfig(List.of("ch1"), null, null, null, "a", "r");
            assertThat(store.getCurrentConfig().getVersion()).isEqualTo(1);

            store.updateConfig(List.of("ch2"), null, null, null, "a", "r");
            assertThat(store.getCurrentConfig().getVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("包含 guildIds 和 ignoreKeywords")
        void withAllFields() {
            store.updateConfig(
                    List.of("ch1"),
                    List.of("guild1"),
                    List.of("author1"),
                    List.of("keyword1"),
                    "admin", "full_update"
            );

            MonitorConfig config = store.getCurrentConfig();
            assertThat(config.getGuildIdsList()).containsExactly("guild1");
            assertThat(config.getAuthorIdsList()).containsExactly("author1");
            assertThat(config.getIgnoreKeywordsList()).containsExactly("keyword1");
        }
    }

    // ==================== Observer 管理 ====================

    @Nested
    @DisplayName("Observer — 連線管理")
    class ObserverTests {

        @Test
        @DisplayName("addObserver 增加連線數")
        void addObserver() {
            @SuppressWarnings("unchecked")
            StreamObserver<ConfigUpdate> observer = mock(StreamObserver.class);
            store.addObserver(observer);
            assertThat(store.getConnectedObservers()).isEqualTo(1);
        }

        @Test
        @DisplayName("removeObserver 減少連線數")
        void removeObserver() {
            @SuppressWarnings("unchecked")
            StreamObserver<ConfigUpdate> observer = mock(StreamObserver.class);
            store.addObserver(observer);
            store.removeObserver(observer);
            assertThat(store.getConnectedObservers()).isEqualTo(0);
        }

        @Test
        @DisplayName("updateConfig 推送到所有 observer")
        void pushToObservers() {
            @SuppressWarnings("unchecked")
            StreamObserver<ConfigUpdate> observer1 = mock(StreamObserver.class);
            @SuppressWarnings("unchecked")
            StreamObserver<ConfigUpdate> observer2 = mock(StreamObserver.class);

            store.addObserver(observer1);
            store.addObserver(observer2);

            store.updateConfig(List.of("ch1"), null, null, null, "admin", "test");

            verify(observer1).onNext(any(ConfigUpdate.class));
            verify(observer2).onNext(any(ConfigUpdate.class));
        }

        @Test
        @DisplayName("dead observer 自動清理")
        void deadObserverCleanup() {
            @SuppressWarnings("unchecked")
            StreamObserver<ConfigUpdate> aliveObserver = mock(StreamObserver.class);
            @SuppressWarnings("unchecked")
            StreamObserver<ConfigUpdate> deadObserver = mock(StreamObserver.class);

            doThrow(new RuntimeException("stream closed")).when(deadObserver).onNext(any());

            store.addObserver(aliveObserver);
            store.addObserver(deadObserver);
            assertThat(store.getConnectedObservers()).isEqualTo(2);

            store.updateConfig(List.of("ch1"), null, null, null, "admin", "test");

            assertThat(store.getConnectedObservers()).isEqualTo(1);
            verify(aliveObserver).onNext(any(ConfigUpdate.class));
        }
    }
}
