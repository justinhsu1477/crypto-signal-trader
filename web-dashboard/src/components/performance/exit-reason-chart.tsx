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
import { useT } from "@/lib/i18n/i18n-context";

interface ExitReasonChartProps {
  data: Record<string, number>;
}

const COLOR_MAP: Record<string, string> = {
  STOP_LOSS: "#ef4444",
  SIGNAL_CLOSE: "#10b981",
  MANUAL_CLOSE: "#3b82f6",
  FAIL_SAFE: "#f59e0b",
};

const DEFAULT_COLOR = "#6b7280";

export function ExitReasonChart({ data }: ExitReasonChartProps) {
  const { t } = useT();

  const LABEL_MAP: Record<string, string> = {
    STOP_LOSS: t("performance.exitStopLoss"),
    SIGNAL_CLOSE: t("performance.exitSignalClose"),
    MANUAL_CLOSE: t("performance.exitManualClose"),
    FAIL_SAFE: "Fail-Safe",
  };

  const entries = Object.entries(data);
  const total = entries.reduce((sum, [, count]) => sum + count, 0);

  const chartData = entries.map(([reason, count]) => ({
    name: LABEL_MAP[reason] ?? reason,
    value: count,
    color: COLOR_MAP[reason] ?? DEFAULT_COLOR,
    percent: total > 0 ? ((count / total) * 100).toFixed(1) : "0",
  }));

  function CustomTooltip({
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
          {t("performance.exitCount", { n: entry.value, pct: entry.payload.percent })}
        </p>
      </div>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("performance.exitReasonDistribution")}</CardTitle>
      </CardHeader>
      <CardContent>
        {chartData.length === 0 ? (
          <p className="text-center text-sm text-muted-foreground">{t("common.noData")}</p>
        ) : (
          <ResponsiveContainer width="100%" height={300}>
            <PieChart>
              <Pie
                data={chartData}
                cx="50%"
                cy="50%"
                outerRadius={100}
                dataKey="value"
                label={({ name, percent }) => `${name} ${percent}%`}
              >
                {chartData.map((entry, index) => (
                  <Cell key={`cell-${index}`} fill={entry.color} />
                ))}
              </Pie>
              <Tooltip content={<CustomTooltip />} />
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
  );
}
