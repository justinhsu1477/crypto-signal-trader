package com.trader.auth.util;

import java.util.Locale;

/**
 * Email 正規化工具
 *
 * 規則：
 * - trim 前後空白
 * - 一律轉為小寫（避免大小寫造成重複帳號或登入失敗）
 */
public final class EmailNormalizer {

    private EmailNormalizer() {}

    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
