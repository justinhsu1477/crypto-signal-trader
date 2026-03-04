package com.trader.auth.dto;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * LINE Profile API 回應
 */
@Data
public class LineProfile {

    @SerializedName("userId")
    private String userId;

    @SerializedName("displayName")
    private String displayName;

    @SerializedName("pictureUrl")
    private String pictureUrl;

    @SerializedName("statusMessage")
    private String statusMessage;
}
