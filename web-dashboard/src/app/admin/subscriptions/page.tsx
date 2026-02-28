"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import {
  getAdminSubscriptions,
  getAdminUserPayments,
  adminActivateSubscription,
  adminCancelSubscription,
  adminSetLifetime,
} from "@/lib/api";
import type {
  AdminSubscriptionListResponse,
  AdminSubscriptionSummary,
  AdminPaymentHistoryResponse,
} from "@/types";
import {
  Users,
  CreditCard,
  Clock,
  Crown,
  Zap,
  XCircle,
  Infinity,
  Receipt,
  Search,
  RefreshCw,
} from "lucide-react";
import { toast } from "sonner";

// ─── Status Badge ───

function StatusBadge({ status }: { status: string }) {
  const config: Record<string, { bg: string; text: string; label: string }> = {
    ACTIVE: { bg: "bg-emerald-500/20", text: "text-emerald-400", label: "Active" },
    TRIALING: { bg: "bg-blue-500/20", text: "text-blue-400", label: "Trialing" },
    LIFETIME: { bg: "bg-purple-500/20", text: "text-purple-400", label: "Lifetime" },
    CANCELLED: { bg: "bg-red-500/20", text: "text-red-400", label: "Cancelled" },
    PAST_DUE: { bg: "bg-yellow-500/20", text: "text-yellow-400", label: "Past Due" },
    NONE: { bg: "bg-zinc-500/20", text: "text-zinc-400", label: "None" },
  };
  const c = config[status] || config.NONE;
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${c.bg} ${c.text}`}>
      {c.label}
    </span>
  );
}

// ─── Activate Dialog ───

function ActivateDialog({
  open,
  user,
  onClose,
  onConfirm,
  t,
}: {
  open: boolean;
  user: AdminSubscriptionSummary | null;
  onClose: () => void;
  onConfirm: (planId: string, days: number) => Promise<void>;
  t: (key: string) => string;
}) {
  const [planId, setPlanId] = useState("basic");
  const [days, setDays] = useState(30);
  const [loading, setLoading] = useState(false);

  if (!open || !user) return null;

  async function handleConfirm() {
    setLoading(true);
    try {
      await onConfirm(planId, days);
      onClose();
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-xl">
        <h3 className="text-lg font-semibold flex items-center gap-2 mb-1">
          <Zap className="h-5 w-5 text-yellow-400" />
          {t("admin.activateTitle")}
        </h3>
        <p className="text-sm text-muted-foreground mb-4">{t("admin.activateDesc")}</p>

        <div className="space-y-3">
          <div>
            <label className="text-xs text-muted-foreground block mb-1">
              {t("admin.email")}
            </label>
            <div className="text-sm font-mono">{user.email}</div>
          </div>

          <div>
            <label className="text-xs text-muted-foreground block mb-1">
              {t("admin.plan")}
            </label>
            <select
              value={planId}
              onChange={(e) => setPlanId(e.target.value)}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="basic">Basic ($99)</option>
              <option value="pro">Pro ($199)</option>
            </select>
          </div>

          <div>
            <label className="text-xs text-muted-foreground block mb-1">
              {t("admin.days")}
            </label>
            <input
              type="number"
              value={days}
              onChange={(e) => setDays(Number(e.target.value))}
              min={1}
              max={365}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
        </div>

        <div className="flex justify-end gap-2 mt-6">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-lg text-sm text-muted-foreground hover:bg-accent transition-colors"
          >
            {t("nav.logoutCancel") || "Cancel"}
          </button>
          <button
            onClick={handleConfirm}
            disabled={loading}
            className="px-4 py-2 rounded-lg text-sm font-medium bg-emerald-500/20 text-emerald-400 hover:bg-emerald-500/30 transition-colors disabled:opacity-50"
          >
            {loading ? "..." : t("admin.confirm")}
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── Payment History Dialog ───

function PaymentDialog({
  open,
  data,
  loading,
  onClose,
  t,
}: {
  open: boolean;
  data: AdminPaymentHistoryResponse | null;
  loading: boolean;
  onClose: () => void;
  t: (key: string) => string;
}) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="w-full max-w-lg rounded-xl border border-border bg-card p-6 shadow-xl max-h-[80vh] overflow-y-auto">
        <h3 className="text-lg font-semibold flex items-center gap-2 mb-1">
          <Receipt className="h-5 w-5 text-blue-400" />
          {t("admin.paymentHistory")}
        </h3>
        {data && (
          <p className="text-sm text-muted-foreground mb-4">
            {data.name || data.email} &mdash; {data.totalPayments} {t("admin.payments")} / ${data.totalAmountPaid} USDT
          </p>
        )}

        {loading ? (
          <div className="flex justify-center py-8">
            <div className="animate-spin rounded-full h-6 w-6 border-b-2 border-primary" />
          </div>
        ) : !data || data.payments.length === 0 ? (
          <div className="text-center py-8 text-muted-foreground text-sm">
            {t("admin.noPayments")}
          </div>
        ) : (
          <div className="space-y-3">
            {data.payments.map((p) => (
              <div key={p.id} className="rounded-lg border border-border/50 p-3">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-mono text-xs truncate max-w-[200px]" title={p.txHash}>
                    {p.txHash}
                  </span>
                  <span className={`text-xs font-medium ${
                    p.status === "succeeded" ? "text-emerald-400" : "text-red-400"
                  }`}>
                    {p.status}
                  </span>
                </div>
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <span>{p.amount} {p.currency} &middot; {p.network}</span>
                  <span>{p.paidAt ? new Date(p.paidAt).toLocaleDateString() : "-"}</span>
                </div>
              </div>
            ))}
          </div>
        )}

        <div className="flex justify-end mt-4">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-lg text-sm text-muted-foreground hover:bg-accent transition-colors"
          >
            {t("admin.close")}
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── Main Page ───

export default function AdminSubscriptionsPage() {
  const { t } = useT();
  const [data, setData] = useState<AdminSubscriptionListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");
  const [processing, setProcessing] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  // Activate dialog
  const [activateTarget, setActivateTarget] = useState<AdminSubscriptionSummary | null>(null);

  // Payment dialog
  const [paymentTarget, setPaymentTarget] = useState<string | null>(null);
  const [paymentData, setPaymentData] = useState<AdminPaymentHistoryResponse | null>(null);
  const [paymentLoading, setPaymentLoading] = useState(false);

  const fetchData = useCallback(() => {
    setLoading(true);
    getAdminSubscriptions()
      .then(setData)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // ─── Filtered list ───
  const filtered = useMemo(() => {
    if (!data) return [];
    if (!searchQuery.trim()) return data.subscriptions;
    const q = searchQuery.toLowerCase();
    return data.subscriptions.filter(
      (s) =>
        s.email?.toLowerCase().includes(q) ||
        s.name?.toLowerCase().includes(q)
    );
  }, [data, searchQuery]);

  // ─── Actions ───

  async function handleActivate(planId: string, days: number) {
    if (!activateTarget) return;
    const userId = activateTarget.userId;
    setProcessing(userId);
    try {
      const res = await adminActivateSubscription(userId, { planId, days });
      toast.success(res.message || t("admin.activateSuccess"));
      fetchData();
    } catch {
      toast.error(t("admin.updateFailed"));
    } finally {
      setProcessing(null);
    }
  }

  async function handleCancel(userId: string) {
    if (!confirm(t("admin.confirmCancel"))) return;
    setProcessing(userId);
    try {
      await adminCancelSubscription(userId);
      toast.success(t("admin.cancelSuccess"));
      fetchData();
    } catch {
      toast.error(t("admin.updateFailed"));
    } finally {
      setProcessing(null);
    }
  }

  async function handleSetLifetime(userId: string) {
    if (!confirm(t("admin.confirmLifetime"))) return;
    setProcessing(userId);
    try {
      await adminSetLifetime(userId);
      toast.success(t("admin.lifetimeSuccess"));
      fetchData();
    } catch {
      toast.error(t("admin.updateFailed"));
    } finally {
      setProcessing(null);
    }
  }

  async function handleViewPayments(userId: string) {
    setPaymentTarget(userId);
    setPaymentLoading(true);
    setPaymentData(null);
    try {
      const res = await getAdminUserPayments(userId);
      setPaymentData(res);
    } catch {
      toast.error(t("admin.updateFailed"));
    } finally {
      setPaymentLoading(false);
    }
  }

  function handleRefresh() {
    setRefreshing(true);
    getAdminSubscriptions()
      .then(setData)
      .catch(() => {})
      .finally(() => setRefreshing(false));
  }

  // ─── Loading ───

  if (loading) {
    return (
      <div className="flex h-[60vh] items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  if (!data) {
    return (
      <div className="flex h-[60vh] items-center justify-center text-muted-foreground">
        Failed to load data
      </div>
    );
  }

  // ─── Stats ───
  const stats = [
    { label: t("admin.totalUsers"), value: data.totalUsers, icon: Users, color: "text-blue-500" },
    { label: t("admin.activeSubscriptions"), value: data.activeSubscriptions, icon: CreditCard, color: "text-emerald-500" },
    { label: t("admin.trialingSubscriptions"), value: data.trialingSubscriptions, icon: Clock, color: "text-yellow-500" },
    { label: t("admin.lifetimeSubscriptions"), value: data.lifetimeSubscriptions, icon: Crown, color: "text-purple-500" },
  ];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t("admin.subscriptionManagement")}</h1>
        <button
          onClick={handleRefresh}
          disabled={refreshing}
          className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
        >
          <RefreshCw className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`} />
        </button>
      </div>

      {/* Stats Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {stats.map((s) => (
          <div key={s.label} className="rounded-xl border border-border bg-card p-4">
            <div className="flex items-center gap-2 mb-1">
              <s.icon className={`h-4 w-4 ${s.color}`} />
              <span className="text-xs text-muted-foreground">{s.label}</span>
            </div>
            <div className="text-xl font-bold">{s.value}</div>
          </div>
        ))}
      </div>

      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder={t("admin.search")}
          className="w-full rounded-xl border border-border bg-card pl-10 pr-4 py-2.5 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
        />
      </div>

      {/* Table */}
      <div className="rounded-xl border border-border bg-card">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="text-left px-4 py-3 font-medium">{t("admin.email")}</th>
                <th className="text-left px-4 py-3 font-medium">{t("admin.name")}</th>
                <th className="text-center px-4 py-3 font-medium">{t("admin.plan")}</th>
                <th className="text-center px-4 py-3 font-medium">{t("admin.status")}</th>
                <th className="text-center px-4 py-3 font-medium">{t("admin.expiresAt")}</th>
                <th className="text-center px-4 py-3 font-medium">{t("admin.payments")}</th>
                <th className="text-center px-4 py-3 font-medium">{t("admin.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((sub) => {
                const isProcessing = processing === sub.userId;
                const isActive = ["ACTIVE", "TRIALING", "LIFETIME"].includes(sub.status);
                return (
                  <tr
                    key={sub.userId}
                    className="border-b border-border/50 hover:bg-accent/30 transition-colors"
                  >
                    <td className="px-4 py-3 font-mono text-xs">{sub.email}</td>
                    <td className="px-4 py-3 text-sm">{sub.name || "-"}</td>
                    <td className="px-4 py-3 text-center text-sm">
                      {sub.planName || sub.planId || "-"}
                    </td>
                    <td className="px-4 py-3 text-center">
                      <StatusBadge status={sub.status} />
                    </td>
                    <td className="px-4 py-3 text-center text-xs text-muted-foreground">
                      {sub.status === "LIFETIME" ? (
                        <span className="text-purple-400 font-medium">{t("admin.forever")}</span>
                      ) : sub.currentPeriodEnd ? (
                        new Date(sub.currentPeriodEnd).toLocaleDateString()
                      ) : (
                        "-"
                      )}
                    </td>
                    <td className="px-4 py-3 text-center text-xs text-muted-foreground">
                      {sub.totalPayments > 0
                        ? `${sub.totalPayments} / $${sub.totalAmountPaid}`
                        : "-"}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-center gap-1">
                        {/* Activate */}
                        <button
                          onClick={() => setActivateTarget(sub)}
                          disabled={isProcessing}
                          title={t("admin.activate")}
                          className="p-1.5 rounded-lg hover:bg-yellow-500/20 text-muted-foreground hover:text-yellow-400 transition-colors disabled:opacity-50"
                        >
                          <Zap className="h-3.5 w-3.5" />
                        </button>

                        {/* Cancel - only for active subscriptions */}
                        {isActive && sub.status !== "LIFETIME" && (
                          <button
                            onClick={() => handleCancel(sub.userId)}
                            disabled={isProcessing}
                            title={t("admin.cancel")}
                            className="p-1.5 rounded-lg hover:bg-red-500/20 text-muted-foreground hover:text-red-400 transition-colors disabled:opacity-50"
                          >
                            <XCircle className="h-3.5 w-3.5" />
                          </button>
                        )}

                        {/* Lifetime - only for non-lifetime users */}
                        {sub.status !== "LIFETIME" && (
                          <button
                            onClick={() => handleSetLifetime(sub.userId)}
                            disabled={isProcessing}
                            title={t("admin.setLifetime")}
                            className="p-1.5 rounded-lg hover:bg-purple-500/20 text-muted-foreground hover:text-purple-400 transition-colors disabled:opacity-50"
                          >
                            <Infinity className="h-3.5 w-3.5" />
                          </button>
                        )}

                        {/* View Payments */}
                        <button
                          onClick={() => handleViewPayments(sub.userId)}
                          disabled={isProcessing}
                          title={t("admin.viewPayments")}
                          className="p-1.5 rounded-lg hover:bg-blue-500/20 text-muted-foreground hover:text-blue-400 transition-colors disabled:opacity-50"
                        >
                          <Receipt className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {/* Dialogs */}
      <ActivateDialog
        open={!!activateTarget}
        user={activateTarget}
        onClose={() => setActivateTarget(null)}
        onConfirm={handleActivate}
        t={t}
      />

      <PaymentDialog
        open={!!paymentTarget}
        data={paymentData}
        loading={paymentLoading}
        onClose={() => {
          setPaymentTarget(null);
          setPaymentData(null);
        }}
        t={t}
      />
    </div>
  );
}
