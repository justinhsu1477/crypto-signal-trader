package com.trader.trading.dto.signalsource;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignUserRequest {

    @NotEmpty(message = "用戶 ID 列表不可為空")
    private List<String> userIds;
}
