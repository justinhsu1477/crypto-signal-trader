"use client";

import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Cell,
} from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { DayOfWeekStats } from "@/types";

interface DayOfWeekChartProps {
  data: DayOfWeekStats[];
}

const DAY_NAME_MAP: Record<string, string> = {
  MONDAY: "週一",
  TUESDAY: "週二",
  WEDNESDAY: "週三",
  THURSDAY: "週四",
  FRIDAY: "週五",
  SATURDAY: "週六",
  SUNDAY: "週日",
};

const DAY_ORDER = [
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
  "SUNDAY",
];

export function DayOfWeekChart({ data }: DayOfWeekChartProps) {
  // Sort by day order and map names
  const sorted = DAY_ORDER.map((day) => {
    const found = data.find((d) => d.dayOfWeek === day);
    return {
      day: DAY_NAME_MAP[day] ?? day,
      netProfit: found?.netProfit ?? 0,
      trades: found?.trades ?? 0,
      winRate: found?.winRate ?? 0,
    };
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>星期績效</CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={280}>
          <BarChart data={sorted}>
            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
            <XAxis dataKey="day" tick={{ fontSize: 12 }} />
            <YAxis tick={{ fontSize: 12 }} />
            <Tooltip
              content={({ active, payload, label }) => {
                if (!active || !payload?.length) return null;
                const item = payload[0].payload as (typeof sorted)[number];
                return (
                  <div className="rounded-lg border bg-card p-3 shadow-md">
                    <p className="mb-1 text-sm font-medium">{label}</p>
                    <p className="text-xs text-muted-foreground">
                      淨利潤:{" "}
                      <span
                        className={
                          item.netProfit >= 0
                            ? "text-emerald-500"
                            : "text-red-500"
                        }
                      >
                        {item.netProfit.toFixed(2)} USDT
                      </span>
                    </p>
                    <p className="text-xs text-muted-foreground">
                      交易數: {item.trades}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      勝率: {item.winRate.toFixed(2)}%
                    </p>
                  </div>
                );
              }}
            />
            <Bar dataKey="netProfit" maxBarSize={40}>
              {sorted.map((entry, index) => (
                <Cell
                  key={`cell-${index}`}
                  fill={entry.netProfit >= 0 ? "#10b981" : "#ef4444"}
                />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  );
}
