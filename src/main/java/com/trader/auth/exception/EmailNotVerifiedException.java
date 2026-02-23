package com.trader.auth.exception;

/**
 * Email 未驗證例外
 *
 * 用於區分 401 (帳密錯誤) 和 403 (Email 未驗證) 的回應
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException(String message) {
        super(message);
    }
}
