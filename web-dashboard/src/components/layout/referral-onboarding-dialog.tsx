"use client";

import { useState } from "react";
import { Copy, Check, Loader2, Clock } from "lucide-react";
import { useReferralGuard } from "@/lib/use-referral-guard";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { submitReferralUid } from "@/lib/api";
import { toast } from "sonner";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";

const ONBOARDING_SEEN_KEY = "referral-onboarding-seen";

function hasSeenOnboarding(): boolean {
  if (typeof window === "undefined") return true;
  return localStorage.getItem(ONBOARDING_SEEN_KEY) === "true";
}

function markOnboardingSeen() {
  if (typeof window !== "undefined") {
    localStorage.setItem(ONBOARDING_SEEN_KEY, "true");
  }
}

/**
 * 首次登入推薦碼引導 Dialog
 * - NOT_STARTED + 未看過引導 → 顯示表單
 * - PENDING → 顯示審核中訊息
 * - VERIFIED / ADMIN / 已看過 → 不顯示
 */
export function ReferralOnboardingDialog() {
  const { role } = useAuth();
  const { isChecking, referralStatus, referralData, refresh } = useReferralGuard(role);
  const { t } = useT();
  const [open, setOpen] = useState(true);
  const [uid, setUid] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [copied, setCopied] = useState<"link" | "code" | null>(null);

  // 不顯示條件：loading / ADMIN / 已驗證 / 已看過引導
  if (isChecking || role === "ADMIN" || referralStatus === "VERIFIED" || hasSeenOnboarding()) {
    return null;
  }

  // 沒有需要顯示的狀態
  if (!referralStatus || !referralData) return null;

  function handleDismiss() {
    markOnboardingSeen();
    setOpen(false);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = uid.trim();
    if (!trimmed) return;

    setSubmitting(true);
    try {
      await submitReferralUid({ exchangeUid: trimmed });
      toast.success(t("referral.submitSuccess"));
      markOnboardingSeen();
      refresh();
      setOpen(false);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("referral.submitError"));
    } finally {
      setSubmitting(false);
    }
  }

  function handleCopy(text: string, type: "link" | "code") {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(type);
      setTimeout(() => setCopied(null), 2000);
    });
  }

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) handleDismiss(); }}>
      <DialogContent showCloseButton={false} className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t("referral.onboardingTitle")}</DialogTitle>
          <DialogDescription>
            {t("referral.onboardingDesc")}
          </DialogDescription>
        </DialogHeader>

        {referralStatus === "PENDING" ? (
          /* 審核中狀態 */
          <div className="flex flex-col items-center gap-3 py-4">
            <Clock className="h-10 w-10 text-yellow-500" />
            <p className="text-sm text-muted-foreground text-center">
              {t("referral.pendingMessage")}
            </p>
            {referralData.exchangeUid && (
              <p className="font-mono text-xs text-muted-foreground">
                UID: {referralData.exchangeUid}
              </p>
            )}
          </div>
        ) : (
          /* NOT_STARTED：顯示推薦碼 + UID 輸入 */
          <div className="space-y-4">
            {/* 推薦碼資訊 */}
            <div className="space-y-2">
              {/* 推薦連結 */}
              <div className="flex items-center gap-2 rounded-lg border border-border bg-muted/50 px-3 py-2">
                <div className="flex-1 min-w-0">
                  <div className="text-[10px] text-muted-foreground uppercase tracking-wider">
                    {t("referral.referralLinkLabel")}
                  </div>
                  <div className="font-mono text-xs truncate">{referralData.referralLink}</div>
                </div>
                <button
                  onClick={() => handleCopy(referralData.referralLink, "link")}
                  className="shrink-0 p-1.5 rounded hover:bg-accent transition-colors"
                >
                  {copied === "link" ? (
                    <Check className="h-3.5 w-3.5 text-green-500" />
                  ) : (
                    <Copy className="h-3.5 w-3.5 text-muted-foreground" />
                  )}
                </button>
              </div>

              {/* 推薦碼 */}
              <div className="flex items-center gap-2 rounded-lg border border-border bg-muted/50 px-3 py-2">
                <div className="flex-1 min-w-0">
                  <div className="text-[10px] text-muted-foreground uppercase tracking-wider">
                    {t("referral.referralCodeLabel")}
                  </div>
                  <div className="font-mono text-sm font-semibold">{referralData.referralCode}</div>
                </div>
                <button
                  onClick={() => handleCopy(referralData.referralCode, "code")}
                  className="shrink-0 p-1.5 rounded hover:bg-accent transition-colors"
                >
                  {copied === "code" ? (
                    <Check className="h-3.5 w-3.5 text-green-500" />
                  ) : (
                    <Copy className="h-3.5 w-3.5 text-muted-foreground" />
                  )}
                </button>
              </div>
            </div>

            {/* UID 輸入 */}
            <form onSubmit={handleSubmit} className="space-y-3">
              <div>
                <label htmlFor="onboarding-uid" className="text-sm font-medium">
                  {t("referral.uidLabel")}
                </label>
                <input
                  id="onboarding-uid"
                  type="text"
                  value={uid}
                  onChange={(e) => setUid(e.target.value)}
                  placeholder={t("referral.uidPlaceholder")}
                  className="mt-1 w-full rounded-lg border border-border bg-background px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-primary/50"
                  disabled={submitting}
                />
              </div>
              <Button type="submit" className="w-full" disabled={!uid.trim() || submitting}>
                {submitting ? (
                  <Loader2 className="h-4 w-4 animate-spin mr-2" />
                ) : null}
                {t("referral.submitUid")}
              </Button>
            </form>
          </div>
        )}

        <DialogFooter>
          <Button variant="ghost" onClick={handleDismiss} className="w-full sm:w-auto">
            {t("referral.skipForNow")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
