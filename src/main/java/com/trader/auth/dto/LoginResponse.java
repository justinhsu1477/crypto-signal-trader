package com.trader.auth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {

    private String token;
    private String refreshToken;
    private long expiresIn;
    private String userId;
    private String email;
}
