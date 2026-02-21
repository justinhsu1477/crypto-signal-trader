"use client";

import { useEffect, useState } from "react";
import type { PlanInfo, SubscriptionStatusDetail } from "@/types";
import {
  getSubscriptionPlans,
  getSubscriptionStatus,
  cancelSubscription,
  upgradeSubscription,
  getCheckoutUrl,
} from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
import { useT } from "@/lib/i18n/i18n-context";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Crown, Zap, Shield, Check } from "lucide-react";

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
  const [message, setMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);

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
  }, []);

  // Handlers
  async function handleCancel() {
    setActionLoading(true);
    setMessage(null);
    try {
      await cancelSubscription();
      setMessage({ type: "success", text: t("settings.cancelSuccess") });
      setCancelDialogOpen(false);
      // Refresh status
      const newStatus = await getSubscriptionStatus();
      setStatus(newStatus);
      onStatusChange?.(newStatus.active);
      const newPlans = await getSubscriptionPlans();
      setPlans(newPlans);
    } catch (err) {
      setMessage({
        type: "error",
        text: err instanceof Error ? err.message : t("common.saveFailed"),
      });
    } finally {
      setActionLoading(false);
    }
  }

  async function handleUpgrade(planId: string) {
    setActionLoading(true);
    setMessage(null);
    try {
      await upgradeSubscription({ planId });
      setMessage({ type: "success", text: t("settings.upgradeSuccess") });
      // Refresh
      const newStatus = await getSubscriptionStatus();
      setStatus(newStatus);
      onStatusChange?.(newStatus.active);
      const newPlans = await getSubscriptionPlans();
      setPlans(newPlans);
    } catch (err) {
      setMessage({
        type: "error",
        text: err instanceof Error ? err.message : t("common.saveFailed"),
      });
    } finally {
      setActionLoading(false);
    }
  }

  async function handleSubscribe(plan: PlanInfo) {
    if (plan.paymentLinkUrl) {
      window.open(plan.paymentLinkUrl, "_blank");
      return;
    }
    // Fallback: use checkout API
    setActionLoading(true);
    try {
      const { checkoutUrl } = await getCheckoutUrl(plan.planId);
      window.open(checkoutUrl, "_blank");
    } catch (err) {
      setMessage({
        type: "error",
        text: err instanceof Error ? err.message : t("common.saveFailed"),
      });
    } finally {
      setActionLoading(false);
    }
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
      // No active subscription → subscribe
      if (plan.priceMonthly === 0) return null; // Free plan, no action needed
      return (
        <Button
          size="sm"
          className="w-full"
          onClick={() => handleSubscribe(plan)}
          disabled={actionLoading}
        >
          {t("settings.subscribe")}
        </Button>
      );
    }

    // Active subscription → upgrade/downgrade
    if (plan.priceMonthly === 0) return null; // Can't switch to free, must cancel
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

      {/* Message */}
      {message && (
        <p
          className={`text-sm ${
            message.type === "success" ? "text-emerald-500" : "text-red-500"
          }`}
        >
          {message.text}
        </p>
      )}

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
                    {plan.priceMonthly === 0
                      ? t("settings.free")
                      : `$${plan.priceMonthly}`}
                  </span>
                  {plan.priceMonthly > 0 && (
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
