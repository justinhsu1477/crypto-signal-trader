package com.trader.referral.service;

import com.trader.referral.config.ReferralConfig;
import com.trader.referral.dto.AdminPendingResponse;
import com.trader.referral.dto.AdminVerifyRequest;
import com.trader.referral.dto.ReferralStatusResponse;
import com.trader.referral.entity.ReferralStatus;
import com.trader.referral.entity.UserExchangeReferralLink;
import com.trader.referral.repository.UserExchangeReferralLinkRepository;
import com.trader.shared.config.AppConstants;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 推薦系統核心服務
 *
 * Single Source of Truth: user_exchange_referral_links 表的 status 欄位
 * 狀態流轉: NOT_STARTED → PENDING → VERIFIED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    private final UserExchangeReferralLinkRepository linkRepository;
    private final UserRepository userRepository;
    private final ReferralConfig referralConfig;

    /**
     * 查詢用戶推薦綁定狀態
     */
    @Transactional(readOnly = true)
    public ReferralStatusResponse getStatus(String userId) {
        String exchange = referralConfig.getDefaultExchange();

        return linkRepository.findByUserIdAndExchange(userId, exchange)
                .map(link -> ReferralStatusResponse.builder()
                        .status(link.getStatus())
                        .exchangeUid(link.getExchangeUid())
                        .verifiedAt(link.getVerifiedAt())
                        .referralLink(referralConfig.getReferralLink())
                        .referralCode(referralConfig.getReferralCode())
                        .build())
                .orElse(ReferralStatusResponse.builder()
                        .status(ReferralStatus.NOT_STARTED)
                        .referralLink(referralConfig.getReferralLink())
                        .referralCode(referralConfig.getReferralCode())
                        .build());
    }

    /**
     * 用戶提交交易所 UID
     *
     * @throws IllegalArgumentException UID 已被其他用戶綁定
     * @throws IllegalStateException 用戶已通過驗證，不可重複提交
     */
    @Transactional
    public ReferralStatusResponse submitUid(String userId, String exchangeUid) {
        String exchange = referralConfig.getDefaultExchange();
        String trimmedUid = exchangeUid.trim();

        // 檢查 UID 唯一性
        if (linkRepository.existsByExchangeAndExchangeUid(exchange, trimmedUid)) {
            // 確認是否為同一用戶的 UID（允許重複提交）
            var existing = linkRepository.findByUserIdAndExchange(userId, exchange);
            if (existing.isEmpty() || !trimmedUid.equals(existing.get().getExchangeUid())) {
                throw new IllegalArgumentException("此交易所 UID 已被其他帳號綁定");
            }
        }

        // 查找或建立 link
        UserExchangeReferralLink link = linkRepository.findByUserIdAndExchange(userId, exchange)
                .orElseGet(() -> UserExchangeReferralLink.builder()
                        .userId(userId)
                        .exchange(exchange)
                        .build());

        // 已驗證不可重複提交
        if (link.getStatus() == ReferralStatus.VERIFIED) {
            throw new IllegalStateException("已通過驗證，不可重複提交");
        }

        link.setExchangeUid(trimmedUid);
        link.setStatus(ReferralStatus.PENDING);
        link.setAdminNotes(null);  // 清空之前的拒絕備註
        try {
            linkRepository.save(link);
        } catch (DataIntegrityViolationException e) {
            log.warn("UID 並行提交衝突: userId={} uid={}", userId, trimmedUid, e);
            throw new IllegalArgumentException("此交易所 UID 已被其他帳號綁定");
        }

        log.info("用戶提交交易所 UID: userId={} exchange={} uid={}", userId, exchange, trimmedUid);

        return ReferralStatusResponse.builder()
                .status(ReferralStatus.PENDING)
                .exchangeUid(trimmedUid)
                .referralLink(referralConfig.getReferralLink())
                .referralCode(referralConfig.getReferralCode())
                .build();
    }

    /**
     * 管理員驗證推薦綁定
     *
     * approved=true → VERIFIED + verifiedAt
     * approved=false → NOT_STARTED + 清空 UID + adminNotes
     *
     * @throws IllegalArgumentException 找不到該用戶的推薦記錄
     */
    @Transactional
    public void adminVerify(String adminId, AdminVerifyRequest request) {
        String exchange = referralConfig.getDefaultExchange();

        UserExchangeReferralLink link = linkRepository
                .findByUserIdAndExchange(request.getUserId(), exchange)
                .orElseThrow(() -> new IllegalArgumentException(
                        "找不到用戶 " + request.getUserId() + " 的推薦記錄"));

        if (request.isApproved()) {
            link.setStatus(ReferralStatus.VERIFIED);
            link.setVerifiedAt(LocalDateTime.now(AppConstants.ZONE_ID));
            link.setAdminNotes(request.getNotes());
            log.info("管理員驗證通過: userId={} uid={} admin={}",
                    request.getUserId(), link.getExchangeUid(), adminId);
        } else {
            link.setStatus(ReferralStatus.NOT_STARTED);
            link.setExchangeUid(null);
            link.setAdminNotes(request.getNotes());
            link.setVerifiedAt(null);
            log.info("管理員拒絕驗證: userId={} reason={} admin={}",
                    request.getUserId(), request.getNotes(), adminId);
        }

        linkRepository.save(link);
    }

    /**
     * 查詢待驗證列表（管理員用）
     */
    @Transactional(readOnly = true)
    public List<AdminPendingResponse> getPendingList() {
        List<UserExchangeReferralLink> pendingLinks =
                linkRepository.findByStatus(ReferralStatus.PENDING);

        return pendingLinks.stream()
                .map(link -> {
                    String email = userRepository.findById(link.getUserId())
                            .map(User::getEmail)
                            .orElse("unknown");
                    return AdminPendingResponse.builder()
                            .userId(link.getUserId())
                            .email(email)
                            .exchangeUid(link.getExchangeUid())
                            .submittedAt(link.getCreatedAt())
                            .build();
                })
                .toList();
    }

    /**
     * 判斷用戶是否已通過推薦碼驗證
     * 供 ReferralVerificationFilter 和其他服務使用
     */
    @Transactional(readOnly = true)
    public boolean isVerified(String userId) {
        return linkRepository.existsByUserIdAndExchangeAndStatus(
                userId, referralConfig.getDefaultExchange(), ReferralStatus.VERIFIED);
    }
}
