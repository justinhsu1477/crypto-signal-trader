package com.trader.user.dto;

import lombok.Data;

@Data
public class AdminUpdateUserRequest {
    private Boolean enabled;
    private Boolean autoTradeEnabled;
    private String role;   // "USER" or "ADMIN"
}
