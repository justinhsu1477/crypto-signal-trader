"use client";

import { useState } from "react";
import Link from "next/link";
import { AlertTriangle, X } from "lucide-react";
import { useReferralGuard } from "@/lib/use-referral-guard";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { Button } from "@/components/ui/button";

const DISMISS_KEY = "referral-banner-dismissed";
const ONBOARDING_SEEN_KEY = "referral-onboarding-seen";

/**
 * 推薦碼軟提醒 Banner
 * - 未驗證用戶：顯示黃色可關閉 banner
 * - 可按 X 關閉（sessionStorage 記住，新 session 重新顯示）
 * - 已驗證 / Admin → 不顯示
 * - 首次引導 Dialog 尚未看過 → 由 Dialog 處理，Banner 隱藏
 */
export function ReferralBanner() {
  const { role } = useAuth();
  const { isChecking, needsReferral } = useReferralGuard(role);
  const { t } = useT();
  const [dismissed, setDismissed] = useState(() => {
    if (typeof window === "undefined") return false;
    return sessionStorage.getItem(DISMISS_KEY) === "true";
  });

  // 首次引導 Dialog 尚未看過 → 由 Dialog 處理，Banner 隱藏
  const onboardingNotSeen = typeof window !== "undefined"
    && localStorage.getItem(ONBOARDING_SEEN_KEY) !== "true";

  // 檢查中 or 不需要 or 已關閉 or Dialog 在處理 → 不顯示
  if (isChecking || !needsReferral || dismissed || onboardingNotSeen) return null;

  function handleDismiss() {
    setDismissed(true);
    sessionStorage.setItem(DISMISS_KEY, "true");
  }

  return (
    <div className="mx-4 md:mx-6 lg:mx-8 mt-4 flex items-center gap-3 rounded-lg border border-yellow-500/25 bg-yellow-500/10 px-4 py-3">
      <AlertTriangle className="h-5 w-5 text-yellow-500 shrink-0" />
      <p className="flex-1 text-sm text-yellow-200">
        {t("referral.bannerMessage")}
      </p>
      <Button variant="outline" size="sm" asChild className="shrink-0 border-yellow-500/30 text-yellow-200 hover:bg-yellow-500/20">
        <Link href="/referral">{t("referral.bannerAction")}</Link>
      </Button>
      <button
        onClick={handleDismiss}
        className="shrink-0 p-1 rounded hover:bg-yellow-500/20 text-yellow-500"
        aria-label="Dismiss"
      >
        <X className="h-4 w-4" />
      </button>
    </div>
  );
}
