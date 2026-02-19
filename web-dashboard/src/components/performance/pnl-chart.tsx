"use client";

import {
  ComposedChart,
  Area,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useT } from "@/lib/i18n/i18n-context";
import type { PnlDataPoint } from "@/types";

interface PnlChartProps {
  data: PnlDataPoint[];
}

function CustomTooltip({
  active,
  payload,
  label,
  t,
}: {
  active?: boolean;
  payload?: Array<{ value: number; dataKey: string }>;
  label?: string;
  t: (key: string) => string;
}) {
  if (!active || !payload?.length) return null;

  const map: Record<string, string> = {
    cumulativePnl: t("performance.cumulativePnl"),
    dailyPnl: t("performance.dailyPnl"),
    drawdown: t("performance.drawdown"),
    drawdownPercent: t("performance.drawdownPercent"),
  };

  return (
    <div className="rounded-lg border bg-card p-3 shadow-md">
      <p className="mb-1 text-sm font-medium">{label}</p>
      {payload.map((entry) => (
        <p key={entry.dataKey} className="text-xs text-muted-foreground">
          {map[entry.dataKey] ?? entry.dataKey}:{" "}
          <span
            className={
              entry.value >= 0 ? "text-emerald-500" : "text-red-500"
            }
          >
            {entry.value.toFixed(2)} USDT
          </span>
        </p>
      ))}
    </div>
  );
}

export function PnlChart({ data }: PnlChartProps) {
  const { t } = useT();

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("performance.pnlCurve")}</CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={350}>
          <ComposedChart data={data}>
            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
            <XAxis
              dataKey="date"
              tick={{ fontSize: 12 }}
              className="text-muted-foreground"
            />
            <YAxis
              tick={{ fontSize: 12 }}
              className="text-muted-foreground"
              tickFormatter={(v: number) => `${v}`}
            />
            <Tooltip content={<CustomTooltip t={t} />} />
            <Area
              type="monotone"
              dataKey="cumulativePnl"
              fill="#10b981"
              fillOpacity={0.15}
              stroke="#10b981"
              strokeWidth={2}
              name={t("performance.cumulativePnl")}
            />
            <Area
              type="monotone"
              dataKey="drawdown"
              fill="#ef4444"
              fillOpacity={0.1}
              stroke="#ef4444"
              strokeWidth={1}
              name={t("performance.drawdown")}
            />
            <Bar
              dataKey="dailyPnl"
              name={t("performance.dailyPnl")}
              maxBarSize={8}
              fill="#10b981"
            />
          </ComposedChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  );
}
