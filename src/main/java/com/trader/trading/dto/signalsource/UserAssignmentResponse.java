package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAssignmentResponse {

    private Long id;
    private String userId;
    private String email;
    private String name;
    private boolean enabled;
    private LocalDateTime assignedAt;
}
