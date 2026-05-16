package com.trader.dashboard.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.annotation.Annotation;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Append-only 護欄：prompt_versions 是 audit chain 的根，永遠不可刪。
 * AdminAuditLog 只存 SHA-256 前 16 hex；要查當時 prompt 全文必須透過
 * prompt_versions.id 查回去。一旦允許 DELETE，整條 audit chain 就斷。
 *
 * <p>此測試用 reflection 檢查 controller 沒有 @DeleteMapping，
 * 若未來有 PR 試圖加 DELETE endpoint 會立刻被 CI 擋下。
 */
class AdminPromptControllerImmutabilityTest {

    @Test
    @DisplayName("AdminPromptController 不可有任何 @DeleteMapping — prompt_versions 是 append-only audit chain")
    void controller_hasNoDeleteMapping() {
        Class<?> clazz = AdminPromptController.class;
        for (Method m : clazz.getDeclaredMethods()) {
            for (Annotation a : m.getAnnotations()) {
                String annotationName = a.annotationType().getSimpleName();
                assertThat(annotationName)
                        .as("Method %s 不應該標 @DeleteMapping — prompt_versions 是 append-only", m.getName())
                        .isNotEqualTo("DeleteMapping");
                // 同時擋住 @RequestMapping(method=DELETE) 的偷渡
                if (annotationName.equals("RequestMapping")) {
                    String s = a.toString();
                    assertThat(s)
                            .as("Method %s @RequestMapping 不可含 DELETE", m.getName())
                            .doesNotContain("DELETE");
                }
            }
        }
    }

    @Test
    @DisplayName("AdminPromptController 公開 HTTP method 僅限 GET + POST")
    void controller_onlyAllowsGetAndPost() {
        Class<?> clazz = AdminPromptController.class;
        for (Method m : clazz.getDeclaredMethods()) {
            for (Annotation a : m.getAnnotations()) {
                String name = a.annotationType().getSimpleName();
                if (name.endsWith("Mapping")) {
                    assertThat(name)
                            .as("Method %s 用了 %s — prompt_versions 只允許 Get/Post (audit chain immutability)",
                                    m.getName(), name)
                            .isIn("GetMapping", "PostMapping", "RequestMapping");
                }
            }
        }
    }
}
