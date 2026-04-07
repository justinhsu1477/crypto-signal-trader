"use client";

import { useEffect, useState } from "react";
import { getPaymentHistory } from "@/lib/api";
import type { UserPaymentHistoryResponse, UserPaymentRecord } from "@/types";
import { useT } from "@/lib/i18n/i18n-context";

export default function PaymentsPage() {
  const { t } = useT();
  const [data, setData] = useState<UserPaymentHistoryResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getPaymentHistory()
      .then(setData)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <div className="p-6 text-muted-foreground">{t("common.loading")}</div>;
  }

  if (!data || data.payments.length === 0) {
    return (
      <div className="p-6">
        <h1 className="text-2xl font-bold mb-6">{t("nav.payments")}</h1>
        <div className="text-muted-foreground">{t("common.noData")}</div>
      </div>
    );
  }

  const statusColor = (status: string) => {
    switch (status) {
      case "succeeded": return "text-green-400";
      case "failed": return "text-red-400";
      case "refunded": return "text-yellow-400";
      default: return "text-muted-foreground";
    }
  };

  const statusLabel = (status: string) => {
    switch (status) {
      case "succeeded": return "Success";
      case "failed": return "Failed";
      case "refunded": return "Refunded";
      default: return status;
    }
  };

  return (
    <div className="p-6 max-w-4xl">
      <h1 className="text-2xl font-bold mb-6">{t("nav.payments")}</h1>

      {/* Summary */}
      <div className="grid grid-cols-2 gap-4 mb-6">
        <div className="bg-card border border-border rounded-lg p-4">
          <div className="text-sm text-muted-foreground">Total Payments</div>
          <div className="text-2xl font-bold">{data.totalPayments}</div>
        </div>
        <div className="bg-card border border-border rounded-lg p-4">
          <div className="text-sm text-muted-foreground">Total Paid</div>
          <div className="text-2xl font-bold text-green-400">
            ${data.totalAmountPaid?.toFixed(2) ?? "0.00"}
          </div>
        </div>
      </div>

      {/* Table */}
      <div className="bg-card border border-border rounded-lg overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-muted/30">
            <tr>
              <th className="text-left px-4 py-3 font-medium">Date</th>
              <th className="text-left px-4 py-3 font-medium">Plan</th>
              <th className="text-right px-4 py-3 font-medium">Amount</th>
              <th className="text-left px-4 py-3 font-medium">Network</th>
              <th className="text-left px-4 py-3 font-medium">Status</th>
              <th className="text-left px-4 py-3 font-medium">Tx Hash</th>
            </tr>
          </thead>
          <tbody>
            {data.payments.map((p: UserPaymentRecord) => (
              <tr key={p.id} className="border-t border-border hover:bg-muted/20">
                <td className="px-4 py-3 text-muted-foreground">
                  {p.paidAt ? new Date(p.paidAt).toLocaleDateString() : "-"}
                </td>
                <td className="px-4 py-3">{p.planId ?? "-"}</td>
                <td className="px-4 py-3 text-right font-mono">
                  {p.amount} {p.currency}
                </td>
                <td className="px-4 py-3 text-muted-foreground">{p.network ?? "-"}</td>
                <td className={`px-4 py-3 font-medium ${statusColor(p.status)}`}>
                  {statusLabel(p.status)}
                </td>
                <td className="px-4 py-3 font-mono text-xs text-muted-foreground max-w-[120px] truncate">
                  {p.txHash ? (
                    <a
                      href={`https://tronscan.org/#/transaction/${p.txHash}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="hover:text-foreground underline"
                    >
                      {p.txHash.slice(0, 10)}...
                    </a>
                  ) : "-"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
