package com.trader.auth.dto;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * LINE Login Token API 回應
 */
@Data
public class LineTokenResponse {

    @SerializedName("access_token")
    private String accessToken;

    @SerializedName("token_type")
    private String tokenType;

    @SerializedName("refresh_token")
    private String refreshToken;

    @SerializedName("expires_in")
    private long expiresIn;

    @SerializedName("id_token")
    private String idToken;

    private String scope;
}
