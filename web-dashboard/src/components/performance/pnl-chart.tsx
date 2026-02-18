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
import type { PnlDataPoint } from "@/types";

interface PnlChartProps {
  data: PnlDataPoint[];
}

function CustomTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean;
  payload?: Array<{ value: number; dataKey: string }>;
  label?: string;
}) {
  if (!active || !payload?.length) return null;

  const map: Record<string, string> = {
    cumulativePnl: "累計盈虧",
    dailyPnl: "當日盈虧",
    drawdown: "回撤",
    drawdownPercent: "回撤 %",
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
  return (
    <Card>
      <CardHeader>
        <CardTitle>盈虧曲線</CardTitle>
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
            <Tooltip content={<CustomTooltip />} />
            <Area
              type="monotone"
              dataKey="cumulativePnl"
              fill="#10b981"
              fillOpacity={0.15}
              stroke="#10b981"
              strokeWidth={2}
              name="累計盈虧"
            />
            <Area
              type="monotone"
              dataKey="drawdown"
              fill="#ef4444"
              fillOpacity={0.1}
              stroke="#ef4444"
              strokeWidth={1}
              name="回撤"
            />
            <Bar
              dataKey="dailyPnl"
              name="當日盈虧"
              maxBarSize={8}
              fill="#10b981"
            />
          </ComposedChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  );
}
