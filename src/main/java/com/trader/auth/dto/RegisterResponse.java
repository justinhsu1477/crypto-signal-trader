package com.trader.auth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RegisterResponse {

    private String userId;
    private String email;
    private String message;
}
