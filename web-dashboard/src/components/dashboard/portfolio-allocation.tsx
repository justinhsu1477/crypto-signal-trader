"use client";

import {
  PieChart,
  Pie,
  Cell,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { useT } from "@/lib/i18n/i18n-context";
import { formatCurrency } from "@/lib/utils";
import type { DashboardOverview } from "@/types";

interface PortfolioAllocationProps {
  data: DashboardOverview;
}

const COLORS = [
  "#8b5cf6", // violet
  "#3b82f6", // blue
  "#10b981", // green
  "#f59e0b", // amber
  "#ef4444", // red
  "#ec4899", // pink
  "#06b6d4", // cyan
  "#f97316", // orange
];

function PortfolioTooltip({
  active,
  payload,
}: {
  active?: boolean;
  payload?: Array<{ name: string; value: number; payload: { percent: string } }>;
}) {
  if (!active || !payload?.length) return null;
  const entry = payload[0];
  return (
    <div className="rounded-lg border bg-card p-3 shadow-md">
      <p className="text-sm font-medium">{entry.name}</p>
      <p className="text-xs text-muted-foreground">
        {formatCurrency(entry.value)} ({entry.payload.percent}%)
      </p>
    </div>
  );
}

function getMarginColor(ratio: number): string {
  if (ratio > 80) return "bg-red-500";
  if (ratio > 50) return "bg-yellow-500";
  return "bg-emerald-500";
}

export function PortfolioAllocation({ data }: PortfolioAllocationProps) {
  const { t } = useT();

  const positions = data.positions ?? [];
  const { totalMarginUsed, marginRatio, availableBalance } = data.account;

  // Pie chart data from position values
  const chartData = positions
    .filter((p) => p.positionValue != null && p.positionValue > 0)
    .map((p) => ({
      name: p.symbol,
      value: p.positionValue!,
      percent: "0",
    }));

  const totalValue = chartData.reduce((sum, d) => sum + d.value, 0);
  chartData.forEach((d) => {
    d.percent = totalValue > 0 ? ((d.value / totalValue) * 100).toFixed(1) : "0";
  });

  return (
    <div className="grid gap-4 md:grid-cols-2">
      {/* Pie Chart */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("dashboard.portfolioAllocation")}</CardTitle>
        </CardHeader>
        <CardContent>
          {chartData.length === 0 ? (
            <p className="text-center text-sm text-muted-foreground py-8">
              {t("dashboard.noPositions")}
            </p>
          ) : (
            <ResponsiveContainer width="100%" height={260}>
              <PieChart>
                <Pie
                  data={chartData}
                  cx="50%"
                  cy="50%"
                  outerRadius={90}
                  dataKey="value"
                  label={({ name, percent }) => `${name} ${percent}%`}
                >
                  {chartData.map((_, index) => (
                    <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                  ))}
                </Pie>
                <Tooltip content={<PortfolioTooltip />} />
                <Legend
                  formatter={(value: string) => (
                    <span className="text-sm">{value}</span>
                  )}
                />
              </PieChart>
            </ResponsiveContainer>
          )}
        </CardContent>
      </Card>

      {/* Margin Usage */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("dashboard.marginUsage")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">{t("dashboard.marginUsed")}</span>
              <span className="font-medium">{formatCurrency(totalMarginUsed)}</span>
            </div>
            <Progress
              value={Math.min(marginRatio, 100)}
              className="h-3"
              indicatorClassName={getMarginColor(marginRatio)}
            />
            <div className="flex justify-between text-xs text-muted-foreground">
              <span>{marginRatio.toFixed(1)}%</span>
              <span>
                {t("dashboard.available")}: {formatCurrency(availableBalance)}
              </span>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
