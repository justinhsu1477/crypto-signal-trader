"use client";

import { useEffect, useState } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAdminSystemOverview, getSystemHealth, getAdminStreamStatus, getAdminDatabaseStats } from "@/lib/api";
import type { AdminSystemOverview, SystemHealthResponse, StreamStatusResponse, DatabaseStatsResponse } from "@/types";
import {
  Users,
  UserCheck,
  TrendingUp,
  Activity,
  BarChart3,
  DollarSign,
  LineChart,
  Hash,
  Database,
  Wifi,
  WifiOff,
  RefreshCw,
  HardDrive,
} from "lucide-react";

function StatusDot({ status }: { status: string }) {
  const color =
    status === "UP" || status === "connected"
      ? "bg-green-500"
      : status === "WARN" || status === "DEGRADED"
        ? "bg-yellow-500"
        : "bg-red-500";
  return <span className={`inline-block h-2.5 w-2.5 rounded-full ${color}`} />;
}

export default function AdminOverviewPage() {
  const { t } = useT();
  const [data, setData] = useState<AdminSystemOverview | null>(null);
  const [health, setHealth] = useState<SystemHealthResponse | null>(null);
  const [stream, setStream] = useState<StreamStatusResponse | null>(null);
  const [dbStats, setDbStats] = useState<DatabaseStatsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [healthLoading, setHealthLoading] = useState(false);

  useEffect(() => {
    Promise.all([
      getAdminSystemOverview().catch(() => null),
      getSystemHealth().catch(() => null),
      getAdminStreamStatus().catch(() => null),
      getAdminDatabaseStats().catch(() => null),
    ]).then(([overview, h, s, db]) => {
      setData(overview);
      setHealth(h);
      setStream(s);
      setDbStats(db);
      setLoading(false);
    });
  }, []);

  function refreshHealth() {
    setHealthLoading(true);
    Promise.all([
      getSystemHealth().catch(() => null),
      getAdminStreamStatus().catch(() => null),
    ]).then(([h, s]) => {
      setHealth(h);
      setStream(s);
      setHealthLoading(false);
    });
  }

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

  const connectedStreams = stream
    ? Object.values(stream.streams).filter((s) => s.connected).length
    : 0;
  const totalStreams = stream?.totalStreams ?? 0;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("admin.systemOverview")}</h1>

      {/* System Health */}
      <div className="rounded-xl border border-border bg-card">
        <div className="flex items-center justify-between p-4 border-b border-border">
          <h2 className="text-lg font-semibold">{t("admin.systemHealth")}</h2>
          <button
            onClick={refreshHealth}
            disabled={healthLoading}
            className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-50"
          >
            <RefreshCw className={`h-4 w-4 ${healthLoading ? "animate-spin" : ""}`} />
          </button>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-0 md:gap-0">
          {/* Database */}
          <div className="p-4 md:border-r border-b md:border-b-0 border-border">
            <div className="flex items-center gap-2 mb-3">
              <Database className="h-4 w-4 text-blue-500" />
              <span className="text-sm font-medium">{t("admin.database")}</span>
              <StatusDot status={health?.database?.status ?? "DOWN"} />
            </div>
            <div className="space-y-1 text-xs text-muted-foreground">
              <div className="flex justify-between">
                <span>Status</span>
                <span className="font-mono">
                  {health?.database?.status === "UP" ? t("admin.healthy") : t("admin.down")}
                </span>
              </div>
              <div className="flex justify-between">
                <span>{t("admin.latency")}</span>
                <span className="font-mono">{health?.database?.latencyMs ?? "-"}ms</span>
              </div>
            </div>
          </div>

          {/* Binance API */}
          <div className="p-4 md:border-r border-b md:border-b-0 border-border">
            <div className="flex items-center gap-2 mb-3">
              <BarChart3 className="h-4 w-4 text-yellow-500" />
              <span className="text-sm font-medium">{t("admin.binanceApi")}</span>
              <StatusDot status={health?.binanceApi?.status ?? "DOWN"} />
            </div>
            <div className="space-y-1 text-xs text-muted-foreground">
              <div className="flex justify-between">
                <span>Status</span>
                <span className="font-mono">
                  {health?.binanceApi?.status === "UP"
                    ? t("admin.healthy")
                    : health?.binanceApi?.status === "WARN"
                      ? t("admin.degraded")
                      : t("admin.down")}
                </span>
              </div>
              <div className="flex justify-between">
                <span>{t("admin.rateLimit")}</span>
                <span className="font-mono">{health?.binanceApi?.usagePercent ?? "-"}</span>
              </div>
            </div>
          </div>

          {/* WebSocket */}
          <div className="p-4">
            <div className="flex items-center gap-2 mb-3">
              {stream?.connected ? (
                <Wifi className="h-4 w-4 text-green-500" />
              ) : (
                <WifiOff className="h-4 w-4 text-red-500" />
              )}
              <span className="text-sm font-medium">{t("admin.webSocket")}</span>
              <StatusDot status={stream?.connected ? "UP" : "DOWN"} />
            </div>
            <div className="space-y-1 text-xs text-muted-foreground">
              <div className="flex justify-between">
                <span>Status</span>
                <span className="font-mono">
                  {stream?.connected ? t("admin.connected") : t("admin.disconnected")}
                </span>
              </div>
              <div className="flex justify-between">
                <span>{t("admin.streams")}</span>
                <span className="font-mono">{connectedStreams}/{totalStreams}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Database Storage */}
      {dbStats && (
        <div className="rounded-xl border border-border bg-card">
          <div className="p-4 border-b border-border">
            <div className="flex items-center gap-2">
              <HardDrive className="h-4 w-4 text-blue-500" />
              <h2 className="text-lg font-semibold">{t("admin.databaseStorage")}</h2>
            </div>
          </div>
          <div className="p-4 space-y-4">
            {/* Usage bar */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm text-muted-foreground">{t("admin.storageUsage")}</span>
                <span className="text-sm font-medium">
                  {(dbStats.totalSizeBytes / 1024 / 1024).toFixed(1)} MB / {(dbStats.storageLimitBytes / 1024 / 1024).toFixed(0)} MB ({dbStats.usagePercent}%)
                </span>
              </div>
              <div className="h-2.5 rounded-full bg-muted overflow-hidden">
                <div
                  className={`h-full rounded-full transition-all ${
                    dbStats.usagePercent > 85
                      ? "bg-red-500"
                      : dbStats.usagePercent > 70
                        ? "bg-yellow-500"
                        : "bg-green-500"
                  }`}
                  style={{ width: `${Math.min(dbStats.usagePercent, 100)}%` }}
                />
              </div>
              <p className="text-xs text-muted-foreground mt-1">{t("admin.neonFreeTier")}</p>
            </div>

            {/* Table breakdown */}
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-muted-foreground">
                    <th className="text-left px-3 py-2 font-medium">{t("admin.tableName")}</th>
                    <th className="text-right px-3 py-2 font-medium">{t("admin.rows")}</th>
                    <th className="text-right px-3 py-2 font-medium">{t("admin.size")}</th>
                  </tr>
                </thead>
                <tbody>
                  {dbStats.tables.map((table) => (
                    <tr key={table.tableName} className="border-b border-border/50">
                      <td className="px-3 py-2 font-mono text-xs">{table.tableName}</td>
                      <td className="px-3 py-2 text-right">{table.rowCount.toLocaleString()}</td>
                      <td className="px-3 py-2 text-right font-mono text-xs">
                        {table.totalBytes >= 1024 * 1024
                          ? `${(table.totalBytes / 1024 / 1024).toFixed(1)} MB`
                          : `${(table.totalBytes / 1024).toFixed(1)} KB`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

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
