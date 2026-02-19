"use client";

import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  Cell,
} from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCurrency, formatPercent } from "@/lib/utils";
import { useT } from "@/lib/i18n/i18n-context";
import type { SideComparison as SideComparisonType } from "@/types";

interface SideComparisonProps {
  data: SideComparisonType;
}

interface StatRowProps {
  label: string;
  value: string;
}

function StatRow({ label, value }: StatRowProps) {
  return (
    <div className="flex justify-between text-sm">
      <span className="text-muted-foreground">{label}</span>
      <span className="font-medium">{value}</span>
    </div>
  );
}

export function SideComparison({ data }: SideComparisonProps) {
  const { t } = useT();
  const { longStats, shortStats } = data;

  const chartData = [
    { name: "LONG", netProfit: longStats.netProfit },
    { name: "SHORT", netProfit: shortStats.netProfit },
  ];

  const colors = ["#10b981", "#ef4444"];

  function ChartTooltip({
    active,
    payload,
  }: {
    active?: boolean;
    payload?: Array<{ value: number; payload: { name: string } }>;
  }) {
    if (!active || !payload?.length) return null;
    const entry = payload[0];
    return (
      <div className="rounded-lg border bg-card p-3 shadow-md">
        <p className="text-sm font-medium">{entry.payload.name}</p>
        <p className="text-xs text-muted-foreground">
          {`${t("performance.netProfit")}: ${entry.value.toFixed(2)} USDT`}
        </p>
      </div>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("performance.longShortComparison")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        {/* Stats columns */}
        <div className="grid grid-cols-2 gap-6">
          {/* LONG */}
          <div className="space-y-2 rounded-lg border border-emerald-500/20 bg-emerald-500/5 p-4">
            <h4 className="text-sm font-semibold text-emerald-500">LONG</h4>
            <StatRow label={t("performance.tradeCount")} value={`${longStats.trades}`} />
            <StatRow label={t("performance.wins")} value={`${longStats.wins}`} />
            <StatRow label={t("performance.winRate")} value={formatPercent(longStats.winRate)} />
            <StatRow label={t("performance.netProfit")} value={`${formatCurrency(longStats.netProfit)} USDT`} />
            <StatRow label={t("performance.avgProfit")} value={`${formatCurrency(longStats.avgProfit)} USDT`} />
            <StatRow label={t("performance.profitFactor")} value={longStats.profitFactor.toFixed(2)} />
          </div>

          {/* SHORT */}
          <div className="space-y-2 rounded-lg border border-red-500/20 bg-red-500/5 p-4">
            <h4 className="text-sm font-semibold text-red-500">SHORT</h4>
            <StatRow label={t("performance.tradeCount")} value={`${shortStats.trades}`} />
            <StatRow label={t("performance.wins")} value={`${shortStats.wins}`} />
            <StatRow label={t("performance.winRate")} value={formatPercent(shortStats.winRate)} />
            <StatRow label={t("performance.netProfit")} value={`${formatCurrency(shortStats.netProfit)} USDT`} />
            <StatRow label={t("performance.avgProfit")} value={`${formatCurrency(shortStats.avgProfit)} USDT`} />
            <StatRow label={t("performance.profitFactor")} value={shortStats.profitFactor.toFixed(2)} />
          </div>
        </div>

        {/* Bar chart */}
        <ResponsiveContainer width="100%" height={120}>
          <BarChart data={chartData} layout="vertical">
            <XAxis type="number" tick={{ fontSize: 12 }} />
            <YAxis
              type="category"
              dataKey="name"
              tick={{ fontSize: 12 }}
              width={50}
            />
            <Tooltip content={<ChartTooltip />} />
            <Bar dataKey="netProfit" maxBarSize={30}>
              {chartData.map((_, index) => (
                <Cell key={`cell-${index}`} fill={colors[index]} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  );
}
