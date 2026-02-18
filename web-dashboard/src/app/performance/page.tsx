"use client";

import { useEffect, useState } from "react";
import { getPerformanceStats } from "@/lib/api";
import { SummaryCards } from "@/components/performance/summary-cards";
import { PnlChart } from "@/components/performance/pnl-chart";
import { SymbolStats } from "@/components/performance/symbol-stats";
import { SideComparison } from "@/components/performance/side-comparison";
import { ExitReasonChart } from "@/components/performance/exit-reason-chart";
import { SignalRanking } from "@/components/performance/signal-ranking";
import { TimeStats } from "@/components/performance/time-stats";
import { DayOfWeekChart } from "@/components/performance/day-of-week-chart";
import { DcaAnalysis } from "@/components/performance/dca-analysis";
import type { PerformanceStats } from "@/types";

const PERIOD_OPTIONS = [
  { label: "7d", days: 7 },
  { label: "30d", days: 30 },
  { label: "90d", days: 90 },
  { label: "全部", days: 3650 },
];

export default function PerformancePage() {
  const [days, setDays] = useState(30);
  const [data, setData] = useState<PerformanceStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function fetchData() {
      setLoading(true);
      setError(null);
      try {
        const stats = await getPerformanceStats(days);
        if (!cancelled) {
          setData(stats);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "載入失敗");
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    fetchData();
    return () => {
      cancelled = true;
    };
  }, [days]);

  if (loading) {
    return (
      <div className="flex h-[50vh] items-center justify-center">
        <p className="text-muted-foreground">載入中...</p>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="flex h-[50vh] items-center justify-center">
        <p className="text-red-500">{error ?? "無法載入資料"}</p>
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6">
      {/* Header with period tabs */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight">績效分析</h1>
        <div className="flex gap-1 rounded-lg bg-muted p-1">
          {PERIOD_OPTIONS.map((option) => (
            <button
              key={option.days}
              onClick={() => setDays(option.days)}
              className={`rounded-md px-3 py-1 text-sm font-medium transition-colors ${
                days === option.days
                  ? "bg-background text-foreground shadow-sm"
                  : "text-muted-foreground hover:text-foreground"
              }`}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>

      {/* Summary Cards */}
      <SummaryCards summary={data.summary} />

      {/* PnL Chart */}
      <PnlChart data={data.pnlCurve} />

      {/* Symbol Stats + Side Comparison */}
      <div className="grid gap-4 lg:grid-cols-2">
        <SymbolStats data={data.symbolStats} />
        <SideComparison data={data.sideComparison} />
      </div>

      {/* Exit Reason + Signal Ranking */}
      <div className="grid gap-4 lg:grid-cols-2">
        <ExitReasonChart data={data.exitReasonBreakdown} />
        <SignalRanking data={data.signalSourceRanking} />
      </div>

      {/* Time Stats */}
      <TimeStats
        weeklyStats={data.weeklyStats}
        monthlyStats={data.monthlyStats}
      />

      {/* Day of Week + DCA Analysis */}
      <div className="grid gap-4 lg:grid-cols-2">
        <DayOfWeekChart data={data.dayOfWeekStats} />
        <DcaAnalysis data={data.dcaAnalysis} />
      </div>
    </div>
  );
}
