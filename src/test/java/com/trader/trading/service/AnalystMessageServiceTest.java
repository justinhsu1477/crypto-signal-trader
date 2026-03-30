package com.trader.trading.service;

import com.trader.trading.entity.AnalystDailyMessage;
import com.trader.trading.repository.AnalystDailyMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalystMessageServiceTest {

    @Mock
    private AnalystDailyMessageRepository repository;

    @InjectMocks
    private AnalystMessageService service;

    @Test
    void appendMessage_createsNewRecord_whenNoneExists() {
        when(repository.findWithLockByAnalystNameAndMessageDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnalystDailyMessage result = service.appendMessage("TestAnalyst", "ch123", "BTC looks bullish");

        assertThat(result.getAnalystName()).isEqualTo("TestAnalyst");
        assertThat(result.getChannelId()).isEqualTo("ch123");
        assertThat(result.getContent()).isEqualTo("BTC looks bullish");
        assertThat(result.getMessageCount()).isEqualTo(1);

        verify(repository).save(any(AnalystDailyMessage.class));
    }

    @Test
    void appendMessage_appendsToExisting_whenRecordExists() {
        AnalystDailyMessage existing = AnalystDailyMessage.builder()
                .id(1L)
                .analystName("TestAnalyst")
                .channelId("ch123")
                .content("First message")
                .messageCount(1)
                .build();

        when(repository.findWithLockByAnalystNameAndMessageDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnalystDailyMessage result = service.appendMessage("TestAnalyst", "ch123", "Second message");

        assertThat(result.getContent()).isEqualTo("First message\n---\nSecond message");
        assertThat(result.getMessageCount()).isEqualTo(2);
    }

    @Test
    void getMessagesByDate_delegatesToRepository() {
        LocalDate date = LocalDate.of(2026, 3, 30);
        List<AnalystDailyMessage> expected = List.of(
                AnalystDailyMessage.builder().analystName("A").build(),
                AnalystDailyMessage.builder().analystName("B").build()
        );
        when(repository.findByMessageDateOrderByAnalystName(date)).thenReturn(expected);

        List<AnalystDailyMessage> result = service.getMessagesByDate(date);

        assertThat(result).hasSize(2);
        verify(repository).findByMessageDateOrderByAnalystName(date);
    }
}
