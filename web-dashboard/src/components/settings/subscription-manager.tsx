"use client";

import { useEffect, useState } from "react";
import type { PlanInfo, SubscriptionStatusDetail, CryptoCheckoutInfo } from "@/types";
import {
  getSubscriptionPlans,
  getSubscriptionStatus,
  cancelSubscription,
  upgradeSubscription,
  getCheckoutInfo,
  submitPayment,
} from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
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
import { Crown, Zap, Shield, Check, Copy, Wallet } from "lucide-react";

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

  // USDT Payment Dialog
  const [paymentDialogOpen, setPaymentDialogOpen] = useState(false);
  const [checkoutInfo, setCheckoutInfo] = useState<CryptoCheckoutInfo | null>(null);
  const [txHash, setTxHash] = useState("");
  const [verifying, setVerifying] = useState(false);

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

  async function handleUpgrade(planId: string) {
    setActionLoading(true);
    try {
      await upgradeSubscription({ planId });
      toast.success(t("settings.upgradeSuccess"));
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
    // 取得付款資訊
    setActionLoading(true);
    try {
      const info = await getCheckoutInfo(plan.planId);
      setCheckoutInfo(info);
      setTxHash("");
      setPaymentDialogOpen(true);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("common.saveFailed"));
    } finally {
      setActionLoading(false);
    }
  }

  async function handleSubmitPayment() {
    if (!checkoutInfo || !txHash.trim()) {
      toast.error("請輸入交易 Hash");
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
      toast.error(err instanceof Error ? err.message : "驗證失敗");
    } finally {
      setVerifying(false);
    }
  }

  function copyToClipboard(text: string) {
    navigator.clipboard.writeText(text);
    toast.success("已複製到剪貼簿");
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
        onClick={() => handleUpgrade(plan.planId)}
        disabled={actionLoading}
      >
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

      {/* USDT Payment Dialog */}
      <Dialog open={paymentDialogOpen} onOpenChange={setPaymentDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Wallet className="h-5 w-5" />
              USDT 付款
            </DialogTitle>
            <DialogDescription>
              請轉帳以下金額到指定錢包地址，完成後貼上交易 Hash
            </DialogDescription>
          </DialogHeader>

          {checkoutInfo && (
            <div className="space-y-4">
              {/* Plan info */}
              <div className="flex justify-between items-center p-3 bg-muted rounded-lg">
                <span className="text-sm text-muted-foreground">方案</span>
                <span className="font-semibold">{checkoutInfo.planName}</span>
              </div>

              {/* Amount */}
              <div className="flex justify-between items-center p-3 bg-emerald-500/10 rounded-lg">
                <span className="text-sm text-muted-foreground">金額</span>
                <span className="text-lg font-bold text-emerald-500">
                  {checkoutInfo.amountUsdt} USDT
                </span>
              </div>

              {/* Network */}
              <div className="flex justify-between items-center p-3 bg-muted rounded-lg">
                <span className="text-sm text-muted-foreground">網路</span>
                <Badge variant="outline">{checkoutInfo.network}</Badge>
              </div>

              {/* Wallet address */}
              <div className="space-y-2">
                <Label className="text-sm text-muted-foreground">收款地址</Label>
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
                <Label htmlFor="txHash">交易 Hash（付款完成後貼上）</Label>
                <Input
                  id="txHash"
                  placeholder="例如: a1b2c3d4e5f6..."
                  value={txHash}
                  onChange={(e) => setTxHash(e.target.value)}
                  disabled={verifying}
                />
              </div>
            </div>
          )}

          <DialogFooter className="gap-2">
            <Button
              variant="outline"
              onClick={() => setPaymentDialogOpen(false)}
              disabled={verifying}
            >
              {t("common.cancel")}
            </Button>
            <Button
              onClick={handleSubmitPayment}
              disabled={verifying || !txHash.trim()}
            >
              {verifying ? "驗證中..." : "提交驗證"}
            </Button>
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
