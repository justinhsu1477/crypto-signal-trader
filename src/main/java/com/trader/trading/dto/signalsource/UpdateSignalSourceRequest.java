package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSignalSourceRequest {

    private String name;
    private String displayName;
    private String description;
    private Boolean enabled;
}
