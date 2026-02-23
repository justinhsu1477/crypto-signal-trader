package com.trader.referral.controller;

import com.trader.referral.dto.AdminPendingResponse;
import com.trader.referral.dto.AdminVerifyRequest;
import com.trader.referral.service.ReferralService;
import com.trader.shared.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理員推薦綁定 API
 *
 * 路徑 /api/admin/referral/** 已被 AuthConfig hasRole("ADMIN") 保護
 */
@RestController
@RequestMapping("/api/admin/referral")
@RequiredArgsConstructor
public class AdminReferralController {

    private final ReferralService referralService;

    /**
     * 管理員驗證推薦綁定（approve / reject）
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verify(@Valid @RequestBody AdminVerifyRequest request) {
        String adminId = SecurityUtil.getCurrentUserId();
        try {
            referralService.adminVerify(adminId, request);
            return ResponseEntity.ok(Map.of(
                    "message", request.isApproved() ? "驗證通過" : "已拒絕",
                    "userId", request.getUserId()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查詢待驗證列表
     */
    @GetMapping("/pending")
    public ResponseEntity<List<AdminPendingResponse>> getPending() {
        return ResponseEntity.ok(referralService.getPendingList());
    }
}
