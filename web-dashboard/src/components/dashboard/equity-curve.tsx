"use client";

import { useCallback, useEffect, useState, useTransition } from "react";
import { getEquityCurve } from "@/lib/api";
import type { BalanceSnapshot } from "@/types";

export function EquityCurve() {
  const [data, setData] = useState<BalanceSnapshot[]>([]);
  const [isPending, startTransition] = useTransition();
  const [days, setDays] = useState(90);

  const fetchData = useCallback((d: number) => {
    startTransition(async () => {
      try {
        const result = await getEquityCurve(d);
        setData(result);
      } catch {
        // ignore
      }
    });
  }, []);

  useEffect(() => {
    fetchData(days);
  }, [days, fetchData]);

  if (isPending && data.length === 0) {
    return (
      <div className="bg-card border border-border rounded-lg p-6">
        <h3 className="text-sm font-semibold mb-4">Equity Curve</h3>
        <div className="h-48 flex items-center justify-center text-muted-foreground text-sm">
          Loading...
        </div>
      </div>
    );
  }

  if (data.length < 2) {
    return (
      <div className="bg-card border border-border rounded-lg p-6">
        <h3 className="text-sm font-semibold mb-4">Equity Curve</h3>
        <div className="h-48 flex items-center justify-center text-muted-foreground text-sm">
          Not enough data yet. Balance snapshots are taken daily.
        </div>
      </div>
    );
  }

  // Simple SVG chart
  const balances = data.map((d) => d.balance);
  const minVal = Math.min(...balances);
  const maxVal = Math.max(...balances);
  const range = maxVal - minVal || 1;
  const width = 600;
  const height = 200;
  const padding = 20;

  const points = data.map((d, i) => {
    const x = padding + (i / (data.length - 1)) * (width - 2 * padding);
    const y = height - padding - ((d.balance - minVal) / range) * (height - 2 * padding);
    return `${x},${y}`;
  });

  const polyline = points.join(" ");
  const lastBalance = balances[balances.length - 1];
  const firstBalance = balances[0];
  const change = lastBalance - firstBalance;
  const changePercent = firstBalance > 0 ? (change / firstBalance) * 100 : 0;
  const isPositive = change >= 0;

  return (
    <div className="bg-card border border-border rounded-lg p-6">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-sm font-semibold">Equity Curve</h3>
          <div className="flex items-center gap-2 mt-1">
            <span className="text-lg font-bold">${lastBalance.toFixed(2)}</span>
            <span className={`text-sm font-medium ${isPositive ? "text-green-400" : "text-red-400"}`}>
              {isPositive ? "+" : ""}{change.toFixed(2)} ({changePercent.toFixed(1)}%)
            </span>
          </div>
        </div>
        <div className="flex gap-1">
          {[30, 90, 180].map((d) => (
            <button
              key={d}
              onClick={() => setDays(d)}
              className={`text-xs px-2 py-1 rounded ${
                days === d
                  ? "bg-primary text-primary-foreground"
                  : "bg-muted text-muted-foreground hover:bg-accent"
              }`}
            >
              {d}d
            </button>
          ))}
        </div>
      </div>

      <svg viewBox={`0 0 ${width} ${height}`} className="w-full h-48">
        {/* Grid lines */}
        {[0.25, 0.5, 0.75].map((pct) => {
          const y = height - padding - pct * (height - 2 * padding);
          return (
            <line
              key={pct}
              x1={padding}
              y1={y}
              x2={width - padding}
              y2={y}
              stroke="currentColor"
              strokeOpacity={0.08}
            />
          );
        })}
        {/* Line */}
        <polyline
          points={polyline}
          fill="none"
          stroke={isPositive ? "#4ade80" : "#f87171"}
          strokeWidth="2"
          strokeLinejoin="round"
        />
      </svg>

      <div className="flex justify-between text-xs text-muted-foreground mt-1">
        <span>{data[0].snapshotDate}</span>
        <span>{data[data.length - 1].snapshotDate}</span>
      </div>
    </div>
  );
}
