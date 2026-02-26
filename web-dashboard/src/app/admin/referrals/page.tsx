"use client";

import { useEffect, useState, useCallback } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAdminPendingReferrals, adminVerifyReferral } from "@/lib/api";
import type { AdminPendingReferral } from "@/types";
import { CheckCircle2, XCircle, Inbox } from "lucide-react";
import { toast } from "sonner";

export default function AdminReferralsPage() {
  const { t } = useT();
  const [items, setItems] = useState<AdminPendingReferral[]>([]);
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState<string | null>(null);

  const fetchPending = useCallback(() => {
    setLoading(true);
    getAdminPendingReferrals()
      .then(setItems)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchPending();
  }, [fetchPending]);

  async function handleVerify(userId: string, approved: boolean) {
    setProcessing(userId);
    try {
      await adminVerifyReferral({ userId, approved });
      toast.success(approved ? t("admin.approveSuccess") : t("admin.rejectSuccess"));
      setItems((prev) => prev.filter((item) => item.userId !== userId));
    } catch {
      toast.error(t("admin.updateFailed"));
    } finally {
      setProcessing(null);
    }
  }

  if (loading) {
    return (
      <div className="flex h-[60vh] items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t("admin.referralReview")}</h1>
        <span className="text-sm text-muted-foreground">
          {items.length} pending
        </span>
      </div>

      {items.length === 0 ? (
        <div className="flex flex-col items-center justify-center h-[40vh] text-muted-foreground gap-3">
          <Inbox className="h-12 w-12" />
          <p>{t("admin.noPending")}</p>
        </div>
      ) : (
        <div className="rounded-xl border border-border bg-card">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-muted-foreground">
                  <th className="text-left px-4 py-3 font-medium">{t("admin.email")}</th>
                  <th className="text-left px-4 py-3 font-medium">{t("admin.exchangeUid")}</th>
                  <th className="text-left px-4 py-3 font-medium">{t("admin.submittedAt")}</th>
                  <th className="text-center px-4 py-3 font-medium">{t("admin.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => {
                  const isProcessing = processing === item.userId;
                  return (
                    <tr
                      key={item.userId}
                      className="border-b border-border/50 hover:bg-accent/30 transition-colors"
                    >
                      <td className="px-4 py-3 font-mono text-xs">
                        {item.email}
                      </td>
                      <td className="px-4 py-3 font-mono text-xs">
                        {item.exchangeUid}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">
                        {new Date(item.submittedAt).toLocaleString()}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center justify-center gap-2">
                          <button
                            onClick={() => handleVerify(item.userId, true)}
                            disabled={isProcessing}
                            className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-green-500/20 text-green-400 hover:bg-green-500/30 text-xs font-medium transition-colors disabled:opacity-50"
                          >
                            <CheckCircle2 className="h-3.5 w-3.5" />
                            {t("admin.approve")}
                          </button>
                          <button
                            onClick={() => handleVerify(item.userId, false)}
                            disabled={isProcessing}
                            className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-red-500/20 text-red-400 hover:bg-red-500/30 text-xs font-medium transition-colors disabled:opacity-50"
                          >
                            <XCircle className="h-3.5 w-3.5" />
                            {t("admin.reject")}
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
      )}
    </div>
  );
}
