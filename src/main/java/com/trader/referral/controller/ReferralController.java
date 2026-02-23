package com.trader.referral.controller;

import com.trader.referral.dto.ReferralStatusResponse;
import com.trader.referral.dto.SubmitUidRequest;
import com.trader.referral.service.ReferralService;
import com.trader.shared.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 推薦綁定 API
 *
 * 所有端點需要 JWT 認證（authenticated）
 * 白名單放行（ReferralVerificationFilter 不攔截 /api/referral/**）
 */
@RestController
@RequestMapping("/api/referral")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;

    /**
     * 查詢推薦綁定狀態
     */
    @GetMapping("/status")
    public ResponseEntity<ReferralStatusResponse> getStatus() {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(referralService.getStatus(userId));
    }

    /**
     * 提交交易所 UID
     */
    @PostMapping("/submit-uid")
    public ResponseEntity<?> submitUid(@Valid @RequestBody SubmitUidRequest request) {
        String userId = SecurityUtil.getCurrentUserId();
        try {
            ReferralStatusResponse result = referralService.submitUid(userId, request.getExchangeUid());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查詢推薦連結 + 推薦碼（公開資訊）
     */
    @GetMapping("/program")
    public ResponseEntity<ReferralStatusResponse> getProgram() {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(referralService.getStatus(userId));
    }
}
