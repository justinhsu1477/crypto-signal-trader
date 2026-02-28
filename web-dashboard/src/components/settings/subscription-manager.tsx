"use client";

import { useCallback, useEffect, useState } from "react";
import type { PlanInfo, SubscriptionStatusDetail, CryptoCheckoutInfo } from "@/types";
import {
  getSubscriptionPlans,
  getSubscriptionStatus,
  cancelSubscription,
  getCheckoutInfo,
  submitPayment,
} from "@/lib/api";
import { cn, formatDateTime } from "@/lib/utils";
import { useT } from "@/lib/i18n/i18n-context";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { toast } from "sonner";
import { Crown, Zap, Shield, Check, Copy, Wallet, Landmark, MessageCircle, RefreshCw } from "lucide-react";

// ===== 台幣轉帳資訊（更改此處即可更新顯示） =====
const TWD_BANK_INFO = {
  bankName: "國泰世華銀行",
  bankCode: "013",
  accountNumber: "0000-0000-0000-0000", // ← 請填入實際帳號
  accountHolder: "—",                   // ← 請填入戶名
};

const ADMIN_CONTACT = "https://lin.ee/9ga4egy"; // ← LINE 官方帳號

// 匯率快取（1 小時有效，避免重複請求）
const RATE_CACHE_TTL = 60 * 60 * 1000; // 1 hour
let cachedRate: { value: number; fetchedAt: number } | null = null;

async function fetchUsdToTwd(): Promise<number> {
  // 快取有效就直接回傳
  if (cachedRate && Date.now() - cachedRate.fetchedAt < RATE_CACHE_TTL) {
    return cachedRate.value;
  }
  // Primary: ExchangeRate-API（免費、無需 API Key、CORS OK）
  try {
    const res = await fetch("https://open.er-api.com/v6/latest/USD");
    if (res.ok) {
      const data = await res.json();
      const rate = data?.rates?.TWD;
      if (typeof rate === "number" && rate > 0) {
        cachedRate = { value: rate, fetchedAt: Date.now() };
        return rate;
      }
    }
  } catch { /* fallback */ }
  // Fallback: fawazahmed0 currency-api（CDN 託管、無頻率限制）
  try {
    const res = await fetch("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json");
    if (res.ok) {
      const data = await res.json();
      const rate = data?.usd?.twd;
      if (typeof rate === "number" && rate > 0) {
        cachedRate = { value: rate, fetchedAt: Date.now() };
        return rate;
      }
    }
  } catch { /* give up */ }
  throw new Error("Failed to fetch exchange rate");
}
// ================================================

type PaymentMethod = "usdt" | "twd";

interface SubscriptionManagerProps {
  onStatusChange?: (active: boolean) => void;
}

export function SubscriptionManager({ onStatusChange }: SubscriptionManagerProps) {
  const { t } = useT();

  // State
  const [plans, setPlans] = useState<PlanInfo[]>([]);
  const [status, setStatus] = useState<SubscriptionStatusDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);

  // Payment Dialog
  const [paymentDialogOpen, setPaymentDialogOpen] = useState(false);
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("usdt");
  const [checkoutInfo, setCheckoutInfo] = useState<CryptoCheckoutInfo | null>(null);
  const [txHash, setTxHash] = useState("");
  const [verifying, setVerifying] = useState(false);

  // Exchange Rate (USD → TWD)
  const [twdRate, setTwdRate] = useState<number | null>(null);
  const [rateLoading, setRateLoading] = useState(false);
  const [rateError, setRateError] = useState(false);

  const loadExchangeRate = useCallback(async () => {
    setRateLoading(true);
    setRateError(false);
    try {
      const rate = await fetchUsdToTwd();
      setTwdRate(rate);
    } catch {
      setRateError(true);
    } finally {
      setRateLoading(false);
    }
  }, []);

  // 切換到台幣 tab 時自動載入匯率
  useEffect(() => {
    if (paymentMethod === "twd" && twdRate === null && !rateLoading) {
      loadExchangeRate();
    }
  }, [paymentMethod, twdRate, rateLoading, loadExchangeRate]);

  // Fetch data
  useEffect(() => {
    let cancelled = false;
    async function fetchData() {
      setLoading(true);
      setError(null);
      try {
        const [plansData, statusData] = await Promise.all([
          getSubscriptionPlans(),
          getSubscriptionStatus(),
        ]);
        if (!cancelled) {
          setPlans(plansData);
          setStatus(statusData);
          onStatusChange?.(statusData.active);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : t("common.loadFailed"));
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    fetchData();
    return () => {
      cancelled = true;
    };
  }, [onStatusChange, t]);

  // Handlers
  async function handleCancel() {
    setActionLoading(true);
    try {
      await cancelSubscription();
      toast.success(t("settings.cancelSuccess"));
      setCancelDialogOpen(false);
      const newStatus = await getSubscriptionStatus();
      setStatus(newStatus);
      onStatusChange?.(newStatus.active);
      const newPlans = await getSubscriptionPlans();
      setPlans(newPlans);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("common.saveFailed"));
    } finally {
      setActionLoading(false);
    }
  }

  async function handleSubscribe(plan: PlanInfo) {
    setActionLoading(true);
    try {
      const info = await getCheckoutInfo(plan.planId);
      setCheckoutInfo(info);
      setTxHash("");
      setPaymentMethod("usdt");
      setPaymentDialogOpen(true);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("common.saveFailed"));
    } finally {
      setActionLoading(false);
    }
  }

  async function handleSubmitPayment() {
    if (!checkoutInfo || !txHash.trim()) {
      toast.error(t("settings.paymentTxRequired"));
      return;
    }

    setVerifying(true);
    try {
      const result = await submitPayment({
        planId: checkoutInfo.planId,
        txHash: txHash.trim(),
      });
      toast.success(result.message);
      setPaymentDialogOpen(false);
      setTxHash("");
      setCheckoutInfo(null);
      // Refresh
      const newStatus = await getSubscriptionStatus();
      setStatus(newStatus);
      onStatusChange?.(newStatus.active);
      const newPlans = await getSubscriptionPlans();
      setPlans(newPlans);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("settings.paymentFailed"));
    } finally {
      setVerifying(false);
    }
  }

  function copyToClipboard(text: string) {
    navigator.clipboard.writeText(text);
    toast.success(t("settings.paymentCopied"));
  }

  // Status badge
  function getStatusBadge() {
    if (!status) return null;
    switch (status.status) {
      case "ACTIVE":
        return (
          <Badge className="bg-emerald-500/15 text-emerald-500 border-emerald-500/25">
            {t("settings.statusActive")}
          </Badge>
        );
      case "PAST_DUE":
        return (
          <Badge className="bg-yellow-500/15 text-yellow-600 border-yellow-500/25">
            {t("settings.statusPastDue")}
          </Badge>
        );
      case "CANCELLED":
        return (
          <Badge variant="destructive">{t("settings.statusCancelled")}</Badge>
        );
      default:
        return (
          <Badge variant="secondary">{t("settings.statusNone")}</Badge>
        );
    }
  }

  // Plan icon
  function getPlanIcon(planId: string) {
    switch (planId) {
      case "pro":
        return <Crown className="h-5 w-5 text-amber-500" />;
      case "basic":
        return <Zap className="h-5 w-5 text-blue-500" />;
      default:
        return <Shield className="h-5 w-5 text-gray-500" />;
    }
  }

  // Plan action button
  function getPlanAction(plan: PlanInfo) {
    if (plan.current) {
      return (
        <Badge variant="outline" className="w-full justify-center py-1.5">
          <Check className="h-3 w-3 mr-1" />
          {t("settings.currentBadge")}
        </Badge>
      );
    }

    const isActive = status?.active;
    const currentPrice = plans.find((p) => p.current)?.priceMonthly ?? 0;
    const isUpgrade = plan.priceMonthly > currentPrice;

    if (!isActive || status?.status === "NONE" || status?.status === "CANCELLED") {
      if (plan.priceMonthly === 0) return null;
      return (
        <Button
          size="sm"
          className="w-full"
          onClick={() => handleSubscribe(plan)}
          disabled={actionLoading}
        >
          <Wallet className="h-4 w-4 mr-1" />
          {t("settings.subscribe")}
        </Button>
      );
    }

    if (plan.priceMonthly === 0) return null;
    return (
      <Button
        size="sm"
        variant={isUpgrade ? "default" : "outline"}
        className="w-full"
        onClick={() => handleSubscribe(plan)}
        disabled={actionLoading}
      >
        <Wallet className="h-4 w-4 mr-1" />
        {isUpgrade ? t("settings.upgrade") : t("settings.downgrade")}
      </Button>
    );
  }

  // Loading
  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  // Error
  if (error) {
    return <div className="text-center py-6 text-red-500">{error}</div>;
  }

  return (
    <div className="space-y-6">
      {/* Current Subscription Status */}
      <div className="flex items-center justify-between p-4 border rounded-lg bg-muted/30">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium">{t("settings.currentPlan")}</span>
            {getStatusBadge()}
          </div>
          {status?.active && status.planName && (
            <p className="text-lg font-semibold">{status.planName}</p>
          )}
          {!status?.active && (
            <p className="text-sm text-muted-foreground">{t("settings.noPlan")}</p>
          )}
          {status?.active && status.currentPeriodEnd && (
            <p className="text-xs text-muted-foreground">
              {t("settings.renewDate", {
                date: formatDateTime(status.currentPeriodEnd),
              })}
            </p>
          )}
        </div>
        {status?.active && (
          <Button
            variant="destructive"
            size="sm"
            onClick={() => setCancelDialogOpen(true)}
            disabled={actionLoading}
          >
            {t("settings.cancelSubscription")}
          </Button>
        )}
      </div>

      <Separator />

      {/* Available Plans */}
      <div>
        <h3 className="text-sm font-medium mb-4">{t("settings.availablePlans")}</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {plans.map((plan) => (
            <Card
              key={plan.planId}
              className={
                plan.current
                  ? "border-primary/50 bg-primary/5"
                  : "border-border"
              }
            >
              <CardHeader className="pb-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    {getPlanIcon(plan.planId)}
                    <CardTitle className="text-base">{plan.name}</CardTitle>
                  </div>
                  {plan.current && (
                    <Badge variant="secondary" className="text-xs">
                      {t("settings.currentBadge")}
                    </Badge>
                  )}
                </div>
                <div className="mt-2">
                  <span className="text-2xl font-bold">
                    {plan.priceUsdt != null && plan.priceUsdt > 0
                      ? `${plan.priceUsdt} USDT`
                      : t("settings.free")}
                  </span>
                  {plan.priceUsdt != null && plan.priceUsdt > 0 && (
                    <span className="text-sm text-muted-foreground">
                      {t("settings.perMonth")}
                    </span>
                  )}
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                {/* Plan limits */}
                <div className="space-y-2 text-sm">
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">
                      {t("settings.positions")}
                    </span>
                    <span className="font-medium">{plan.maxPositions}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">
                      {t("settings.symbols")}
                    </span>
                    <span className="font-medium">{plan.maxSymbols}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">
                      {t("settings.dcaLayers")}
                    </span>
                    <span className="font-medium">{plan.dcaLayersAllowed}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">
                      {t("settings.riskLimit")}
                    </span>
                    <span className="font-medium">
                      {plan.maxRiskPercent != null
                        ? `${(plan.maxRiskPercent * 100).toFixed(0)}%`
                        : "-"}
                    </span>
                  </div>
                </div>

                <Separator />

                {/* Action button */}
                {getPlanAction(plan)}
              </CardContent>
            </Card>
          ))}
        </div>
      </div>

      {/* Payment Dialog (USDT + TWD) */}
      <Dialog open={paymentDialogOpen} onOpenChange={setPaymentDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              {paymentMethod === "usdt" ? (
                <Wallet className="h-5 w-5" />
              ) : (
                <Landmark className="h-5 w-5" />
              )}
              {paymentMethod === "usdt"
                ? t("settings.paymentTitle")
                : t("settings.payMethodTwd")}
            </DialogTitle>
            <DialogDescription>
              {paymentMethod === "usdt"
                ? t("settings.paymentDescription")
                : t("settings.twdNote")}
            </DialogDescription>
          </DialogHeader>

          {/* Payment Method Tabs */}
          <div className="flex gap-1 p-1 bg-muted rounded-lg">
            <button
              className={cn(
                "flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-md text-sm font-medium transition-colors",
                paymentMethod === "usdt"
                  ? "bg-background shadow-sm text-foreground"
                  : "text-muted-foreground hover:text-foreground"
              )}
              onClick={() => setPaymentMethod("usdt")}
            >
              <Wallet className="h-4 w-4" />
              {t("settings.payMethodUsdt")}
            </button>
            <button
              className={cn(
                "flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-md text-sm font-medium transition-colors",
                paymentMethod === "twd"
                  ? "bg-background shadow-sm text-foreground"
                  : "text-muted-foreground hover:text-foreground"
              )}
              onClick={() => setPaymentMethod("twd")}
            >
              <Landmark className="h-4 w-4" />
              {t("settings.payMethodTwd")}
            </button>
          </div>

          {/* ─── USDT Payment Content ─── */}
          {paymentMethod === "usdt" && checkoutInfo && (
            <div className="space-y-4">
              {/* Plan info */}
              <div className="flex justify-between items-center p-3 bg-muted rounded-lg">
                <span className="text-sm text-muted-foreground">{t("settings.paymentPlan")}</span>
                <span className="font-semibold">{checkoutInfo.planName}</span>
              </div>

              {/* Amount */}
              <div className="flex justify-between items-center p-3 bg-emerald-500/10 rounded-lg">
                <span className="text-sm text-muted-foreground">{t("settings.paymentAmount")}</span>
                <span className="text-lg font-bold text-emerald-500">
                  {checkoutInfo.amountUsdt} USDT
                </span>
              </div>

              {/* Network */}
              <div className="flex justify-between items-center p-3 bg-muted rounded-lg">
                <span className="text-sm text-muted-foreground">{t("settings.paymentNetwork")}</span>
                <Badge variant="outline">{checkoutInfo.network}</Badge>
              </div>

              {/* Wallet address */}
              <div className="space-y-2">
                <Label className="text-sm text-muted-foreground">{t("settings.paymentAddress")}</Label>
                <div className="flex items-center gap-2">
                  <code className="flex-1 p-2 bg-muted rounded text-xs break-all font-mono">
                    {checkoutInfo.walletAddress}
                  </code>
                  <Button
                    size="icon"
                    variant="outline"
                    onClick={() => copyToClipboard(checkoutInfo.walletAddress)}
                  >
                    <Copy className="h-4 w-4" />
                  </Button>
                </div>
              </div>

              <Separator />

              {/* TX Hash input */}
              <div className="space-y-2">
                <Label htmlFor="txHash">{t("settings.paymentTxHash")}</Label>
                <Input
                  id="txHash"
                  placeholder={t("settings.paymentTxPlaceholder")}
                  value={txHash}
                  onChange={(e) => setTxHash(e.target.value)}
                  disabled={verifying}
                />
              </div>
            </div>
          )}

          {/* ─── TWD Bank Transfer Content ─── */}
          {paymentMethod === "twd" && checkoutInfo && (
            <div className="space-y-4">
              {/* Plan info */}
              <div className="flex justify-between items-center p-3 bg-muted rounded-lg">
                <span className="text-sm text-muted-foreground">{t("settings.paymentPlan")}</span>
                <span className="font-semibold">{checkoutInfo.planName}</span>
              </div>

              {/* TWD Amount (即時匯率) */}
              <div className="p-3 bg-blue-500/10 rounded-lg space-y-1">
                <div className="flex justify-between items-center">
                  <span className="text-sm text-muted-foreground">{t("settings.twdAmount")}</span>
                  {rateLoading ? (
                    <div className="flex items-center gap-2 text-blue-500">
                      <RefreshCw className="h-4 w-4 animate-spin" />
                      <span className="text-sm">{t("common.loading")}</span>
                    </div>
                  ) : rateError ? (
                    <button
                      onClick={loadExchangeRate}
                      className="flex items-center gap-1 text-sm text-red-400 hover:text-red-300 transition-colors"
                    >
                      <RefreshCw className="h-3.5 w-3.5" />
                      {t("common.retry")}
                    </button>
                  ) : twdRate ? (
                    <span className="text-lg font-bold text-blue-500">
                      ≈ NT$ {Math.round(checkoutInfo.amountUsdt * twdRate).toLocaleString()}
                    </span>
                  ) : null}
                </div>
                {twdRate && !rateLoading && (
                  <div className="flex justify-between items-center">
                    <span className="text-xs text-muted-foreground">
                      1 USDT ≈ {twdRate.toFixed(2)} TWD
                    </span>
                    <button
                      onClick={loadExchangeRate}
                      className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                    >
                      <RefreshCw className="h-3 w-3" />
                      {t("settings.twdRefreshRate")}
                    </button>
                  </div>
                )}
              </div>

              {/* Bank Info Card */}
              <div className="space-y-3 p-4 border rounded-lg bg-muted/30">
                <div className="flex justify-between items-center">
                  <span className="text-sm text-muted-foreground">{t("settings.twdBankName")}</span>
                  <span className="text-sm font-medium">
                    {TWD_BANK_INFO.bankName}
                  </span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-sm text-muted-foreground">{t("settings.twdBranchCode")}</span>
                  <Badge variant="outline">{TWD_BANK_INFO.bankCode}</Badge>
                </div>
                <Separator />
                <div className="flex justify-between items-center">
                  <span className="text-sm text-muted-foreground">{t("settings.twdAccountNumber")}</span>
                  <div className="flex items-center gap-2">
                    <code className="text-sm font-mono">{TWD_BANK_INFO.accountNumber}</code>
                    <Button
                      size="icon"
                      variant="ghost"
                      className="h-7 w-7"
                      onClick={() => copyToClipboard(TWD_BANK_INFO.accountNumber.replace(/-/g, ""))}
                    >
                      <Copy className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-sm text-muted-foreground">{t("settings.twdAccountHolder")}</span>
                  <span className="text-sm font-medium">{TWD_BANK_INFO.accountHolder}</span>
                </div>
              </div>

              {/* Note */}
              <div className="p-3 bg-amber-500/10 border border-amber-500/20 rounded-lg space-y-1">
                <p className="text-sm font-medium text-amber-600 dark:text-amber-400">
                  ⚠️ {t("settings.twdNote")}
                </p>
                <p className="text-xs text-muted-foreground">
                  {t("settings.twdNoteDetail")}
                </p>
              </div>
            </div>
          )}

          <DialogFooter className="gap-2">
            <Button
              variant="outline"
              onClick={() => setPaymentDialogOpen(false)}
              disabled={verifying}
            >
              {t("common.close")}
            </Button>
            {paymentMethod === "usdt" && (
              <Button
                onClick={handleSubmitPayment}
                disabled={verifying || !txHash.trim()}
              >
                {verifying ? t("settings.paymentVerifying") : t("settings.paymentSubmit")}
              </Button>
            )}
            {paymentMethod === "twd" && (
              <Button
                onClick={() => window.open(ADMIN_CONTACT, "_blank")}
              >
                <MessageCircle className="h-4 w-4 mr-1" />
                {t("settings.twdContactAdmin")}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Cancel Confirmation Dialog */}
      <Dialog open={cancelDialogOpen} onOpenChange={setCancelDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("settings.cancelConfirmTitle")}</DialogTitle>
            <DialogDescription>
              {t("settings.cancelConfirmMessage")}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setCancelDialogOpen(false)}
              disabled={actionLoading}
            >
              {t("common.cancel")}
            </Button>
            <Button
              variant="destructive"
              onClick={handleCancel}
              disabled={actionLoading}
            >
              {actionLoading ? t("common.saving") : t("settings.cancelConfirmButton")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
