"use client";

import { useEffect, useState } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAdminFunnelStats } from "@/lib/api";
import { Users2 } from "lucide-react";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  AreaChart,
  Area,
  Cell,
} from "recharts";
import type { FunnelStatsResponse } from "@/types";

const FUNNEL_COLORS = [
  "#8b5cf6", // purple - registered
  "#6366f1", // indigo - email verified
  "#3b82f6", // blue - referral verified
  "#06b6d4", // cyan - api key
  "#10b981", // green - traded
  "#f59e0b", // amber - subscribed
];

export default function AdminInsightsPage() {
  const { t } = useT();
  const [data, setData] = useState<FunnelStatsResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getAdminFunnelStats()
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
      <div className="flex h-[60vh] items-center justify-center">
        <p className="text-red-500">{t("common.cannotLoad")}</p>
      </div>
    );
  }

  // Build funnel data
  const funnelStages = [
    { key: "registered", label: t("insights.stageRegistered"), value: data.totalUsers },
    { key: "emailVerified", label: t("insights.stageEmailVerified"), value: data.emailVerified },
    { key: "referralVerified", label: t("insights.stageReferralVerified"), value: data.referralVerified },
    { key: "apiKey", label: t("insights.stageApiKey"), value: data.hasApiKey },
    { key: "traded", label: t("insights.stageTraded"), value: data.hasTraded },
    { key: "subscribed", label: t("insights.stageSubscribed"), value: data.activeSubscription },
  ];

  // Calculate conversion rates
  const funnelData = funnelStages.map((stage, i) => {
    const prev = i > 0 ? funnelStages[i - 1].value : stage.value;
    const rate = prev > 0 ? ((stage.value / prev) * 100).toFixed(1) : "0";
    return {
      ...stage,
      rate: i === 0 ? "100" : rate,
    };
  });

  // Stage label map for recent users table
  const stageLabels: Record<string, string> = {
    registered: t("insights.stageRegistered"),
    email_verified: t("insights.stageEmailVerified"),
    referral_verified: t("insights.stageReferralVerified"),
    api_key_set: t("insights.stageApiKey"),
    traded: t("insights.stageTraded"),
    subscribed: t("insights.stageSubscribed"),
  };

  const stageColors: Record<string, string> = {
    registered: "bg-purple-500/20 text-purple-200",
    email_verified: "bg-indigo-500/20 text-indigo-200",
    referral_verified: "bg-blue-500/20 text-blue-200",
    api_key_set: "bg-cyan-500/20 text-cyan-200",
    traded: "bg-green-500/20 text-green-200",
    subscribed: "bg-amber-500/20 text-amber-200",
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Users2 className="h-6 w-6 text-purple-400" />
        <h1 className="text-2xl font-bold">{t("insights.title")}</h1>
      </div>

      {/* Funnel Chart */}
      <div className="rounded-xl border border-border bg-card p-6">
        <h2 className="text-lg font-semibold mb-4">{t("insights.funnelTitle")}</h2>
        <div className="h-[320px]">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart
              data={funnelData}
              layout="vertical"
              margin={{ top: 5, right: 60, left: 0, bottom: 5 }}
            >
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" horizontal={false} />
              <XAxis type="number" stroke="hsl(var(--muted-foreground))" fontSize={12} />
              <YAxis
                type="category"
                dataKey="label"
                width={120}
                stroke="hsl(var(--foreground))"
                fontSize={12}
                tickLine={false}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: "hsl(var(--card))",
                  borderColor: "hsl(var(--border))",
                  borderRadius: "0.5rem",
                  fontSize: "0.875rem",
                }}
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                formatter={(value: any, _name: any, props: any) => [
                  `${value} (${props?.payload?.rate ?? ""}%)`,
                  "",
                ]}
              />
              <Bar dataKey="value" radius={[0, 4, 4, 0]} maxBarSize={36}>
                {funnelData.map((_entry, index) => (
                  <Cell key={index} fill={FUNNEL_COLORS[index]} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>

        {/* Conversion rate badges */}
        <div className="flex flex-wrap gap-2 mt-4">
          {funnelData.map((stage, i) => (
            <div
              key={stage.key}
              className="flex items-center gap-2 rounded-lg px-3 py-1.5 text-xs"
              style={{ backgroundColor: `${FUNNEL_COLORS[i]}15`, color: FUNNEL_COLORS[i] }}
            >
              <span className="font-medium">{stage.label}</span>
              <span className="font-mono">{stage.value}</span>
              {i > 0 && (
                <span className="opacity-70">({stage.rate}% {t("insights.conversionRate")})</span>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Registration Trend */}
      <div className="rounded-xl border border-border bg-card p-6">
        <h2 className="text-lg font-semibold mb-4">{t("insights.registrationTrend")}</h2>
        {data.registrationsByDate.length > 0 ? (
          <div className="h-[240px]">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={data.registrationsByDate} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                <defs>
                  <linearGradient id="regGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#8b5cf6" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="#8b5cf6" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
                <XAxis
                  dataKey="date"
                  stroke="hsl(var(--muted-foreground))"
                  fontSize={11}
                  tickFormatter={(v: string) => {
                    const parts = v.split("-");
                    return `${parts[1]}/${parts[2]}`;
                  }}
                  interval="preserveStartEnd"
                />
                <YAxis
                  stroke="hsl(var(--muted-foreground))"
                  fontSize={12}
                  allowDecimals={false}
                />
                <Tooltip
                  contentStyle={{
                    backgroundColor: "hsl(var(--card))",
                    borderColor: "hsl(var(--border))",
                    borderRadius: "0.5rem",
                    fontSize: "0.875rem",
                  }}
                  // eslint-disable-next-line @typescript-eslint/no-explicit-any
                  formatter={(value: any) => [value, t("insights.registrations")]}
                />
                <Area
                  type="monotone"
                  dataKey="count"
                  stroke="#8b5cf6"
                  fill="url(#regGradient)"
                  strokeWidth={2}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <div className="flex items-center justify-center h-[200px] text-muted-foreground text-sm">
            {t("common.noData")}
          </div>
        )}
      </div>

      {/* Recent Registrations */}
      <div className="rounded-xl border border-border bg-card">
        <div className="px-6 py-4 border-b border-border">
          <h2 className="text-lg font-semibold">{t("insights.recentRegistrations")}</h2>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="text-left px-4 py-3 font-medium">{t("admin.userLabel")}</th>
                <th className="text-left px-4 py-3 font-medium">{t("admin.createdAt")}</th>
                <th className="text-left px-4 py-3 font-medium">{t("insights.stage")}</th>
              </tr>
            </thead>
            <tbody>
              {data.recentUsers.map((user) => (
                <tr
                  key={user.userId}
                  className="border-b border-border/50 hover:bg-accent/30 transition-colors"
                >
                  <td className="px-4 py-3">
                    <div className="text-sm font-medium">{user.name || "unknown"}</div>
                    <div className="mt-0.5">
                      {user.email ? (
                        <span className="font-mono text-xs text-muted-foreground">{user.email}</span>
                      ) : (
                        <span className="inline-block px-1.5 py-px rounded bg-green-500/15 text-green-200 text-[10px] font-medium">
                          LINE
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">
                    {user.createdAt ? new Date(user.createdAt).toLocaleString() : "-"}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`inline-block px-2 py-0.5 rounded text-[11px] font-medium ${
                        stageColors[user.stage] || "bg-muted text-muted-foreground"
                      }`}
                    >
                      {stageLabels[user.stage] || user.stage}
                    </span>
                  </td>
                </tr>
              ))}
              {data.recentUsers.length === 0 && (
                <tr>
                  <td colSpan={3} className="px-4 py-8 text-center text-muted-foreground">
                    {t("common.noData")}
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
