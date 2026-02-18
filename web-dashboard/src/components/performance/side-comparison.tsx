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
        淨利潤: {entry.value.toFixed(2)} USDT
      </p>
    </div>
  );
}

export function SideComparison({ data }: SideComparisonProps) {
  const { longStats, shortStats } = data;

  const chartData = [
    { name: "LONG", netProfit: longStats.netProfit },
    { name: "SHORT", netProfit: shortStats.netProfit },
  ];

  const colors = ["#10b981", "#ef4444"];

  return (
    <Card>
      <CardHeader>
        <CardTitle>多空對比</CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        {/* Stats columns */}
        <div className="grid grid-cols-2 gap-6">
          {/* LONG */}
          <div className="space-y-2 rounded-lg border border-emerald-500/20 bg-emerald-500/5 p-4">
            <h4 className="text-sm font-semibold text-emerald-500">LONG</h4>
            <StatRow label="交易數" value={`${longStats.trades}`} />
            <StatRow label="勝場" value={`${longStats.wins}`} />
            <StatRow label="勝率" value={formatPercent(longStats.winRate)} />
            <StatRow label="淨利潤" value={`${formatCurrency(longStats.netProfit)} USDT`} />
            <StatRow label="平均利潤" value={`${formatCurrency(longStats.avgProfit)} USDT`} />
            <StatRow label="盈虧比" value={longStats.profitFactor.toFixed(2)} />
          </div>

          {/* SHORT */}
          <div className="space-y-2 rounded-lg border border-red-500/20 bg-red-500/5 p-4">
            <h4 className="text-sm font-semibold text-red-500">SHORT</h4>
            <StatRow label="交易數" value={`${shortStats.trades}`} />
            <StatRow label="勝場" value={`${shortStats.wins}`} />
            <StatRow label="勝率" value={formatPercent(shortStats.winRate)} />
            <StatRow label="淨利潤" value={`${formatCurrency(shortStats.netProfit)} USDT`} />
            <StatRow label="平均利潤" value={`${formatCurrency(shortStats.avgProfit)} USDT`} />
            <StatRow label="盈虧比" value={shortStats.profitFactor.toFixed(2)} />
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
