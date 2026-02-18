package com.trader.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 統一錯誤回應格式
 *
 * 所有 API 錯誤都使用此格式回傳，
 * 讓前端可以統一解析錯誤。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    /** 錯誤類型（簡短描述） */
    private String error;

    /** 詳細錯誤訊息 */
    private String message;
}
