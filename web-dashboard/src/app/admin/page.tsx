"use client";

import { useEffect, useState } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAdminSystemOverview } from "@/lib/api";
import type { AdminSystemOverview } from "@/types";
import {
  Users,
  UserCheck,
  TrendingUp,
  Activity,
  BarChart3,
  DollarSign,
  LineChart,
  Hash,
} from "lucide-react";

export default function AdminOverviewPage() {
  const { t } = useT();
  const [data, setData] = useState<AdminSystemOverview | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getAdminSystemOverview()
      .then(setData)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

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

  const kpis = [
    { label: t("admin.totalUsers"), value: data.totalUsers, icon: Users, color: "text-blue-500" },
    { label: t("admin.activeUsers"), value: data.activeUsers, icon: UserCheck, color: "text-green-500" },
    { label: t("admin.usersWithPositions"), value: data.usersWithOpenPositions, icon: Activity, color: "text-orange-500" },
    { label: t("admin.totalOpenPositions"), value: data.totalOpenPositions, icon: BarChart3, color: "text-purple-500" },
    { label: t("admin.totalClosedTrades"), value: data.totalClosedTrades.toLocaleString(), icon: Hash, color: "text-cyan-500" },
    {
      label: t("admin.totalNetProfit"),
      value: `${data.totalNetProfit >= 0 ? "+" : ""}${data.totalNetProfit.toFixed(2)}`,
      icon: DollarSign,
      color: data.totalNetProfit >= 0 ? "text-green-500" : "text-red-500",
    },
    {
      label: t("admin.todayPnl"),
      value: `${data.todayNetProfit >= 0 ? "+" : ""}${data.todayNetProfit.toFixed(2)}`,
      icon: LineChart,
      color: data.todayNetProfit >= 0 ? "text-green-500" : "text-red-500",
    },
    { label: t("admin.todayTrades"), value: data.todayTradeCount, icon: TrendingUp, color: "text-yellow-500" },
  ];

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("admin.systemOverview")}</h1>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {kpis.map((kpi) => (
          <div
            key={kpi.label}
            className="rounded-xl border border-border bg-card p-4"
          >
            <div className="flex items-center gap-2 mb-2">
              <kpi.icon className={`h-4 w-4 ${kpi.color}`} />
              <span className="text-xs text-muted-foreground">{kpi.label}</span>
            </div>
            <div className="text-xl font-bold">{kpi.value}</div>
          </div>
        ))}
      </div>

      {/* User Summaries Table */}
      <div className="rounded-xl border border-border bg-card">
        <div className="p-4 border-b border-border">
          <h2 className="text-lg font-semibold">{t("admin.userSummaries")}</h2>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="text-left px-4 py-3 font-medium">{t("admin.email")}</th>
                <th className="text-left px-4 py-3 font-medium">{t("admin.name")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.openPositions")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.closedTrades")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.netProfit")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.todayPnl")}</th>
              </tr>
            </thead>
            <tbody>
              {data.userSummaries.map((user) => (
                <tr
                  key={user.userId}
                  className="border-b border-border/50 hover:bg-accent/30 transition-colors"
                >
                  <td className="px-4 py-3 font-mono text-xs">{user.email}</td>
                  <td className="px-4 py-3">{user.name || "-"}</td>
                  <td className="px-4 py-3 text-right">{user.openPositionCount}</td>
                  <td className="px-4 py-3 text-right">{user.closedTradeCount}</td>
                  <td
                    className={`px-4 py-3 text-right font-medium ${
                      user.totalNetProfit >= 0 ? "text-green-500" : "text-red-500"
                    }`}
                  >
                    {user.totalNetProfit >= 0 ? "+" : ""}
                    {user.totalNetProfit.toFixed(2)}
                  </td>
                  <td
                    className={`px-4 py-3 text-right font-medium ${
                      user.todayPnl >= 0 ? "text-green-500" : "text-red-500"
                    }`}
                  >
                    {user.todayPnl >= 0 ? "+" : ""}
                    {user.todayPnl.toFixed(2)}
                  </td>
                </tr>
              ))}
              {data.userSummaries.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-8 text-center text-muted-foreground">
                    No users found
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
