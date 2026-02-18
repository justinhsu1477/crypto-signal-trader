"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCurrency, formatPercent, pnlColor } from "@/lib/utils";
import type { PerformanceSummary } from "@/types";

interface SummaryCardsProps {
  summary: PerformanceSummary;
}

export function SummaryCards({ summary }: SummaryCardsProps) {
  const mainCards = [
    {
      title: "勝率",
      value: formatPercent(summary.winRate),
      subtitle: `${summary.winningTrades}W / ${summary.totalTrades}T`,
      color: summary.winRate >= 50 ? "text-emerald-500" : "text-red-500",
    },
    {
      title: "盈虧比 (Profit Factor)",
      value: summary.profitFactor.toFixed(2),
      subtitle: "盈利 / 虧損",
      color: summary.profitFactor >= 1 ? "text-emerald-500" : "text-red-500",
    },
    {
      title: "淨利潤",
      value: `${formatCurrency(summary.totalNetProfit)} USDT`,
      subtitle: `佣金 ${summary.totalCommission.toFixed(2)} USDT`,
      color: pnlColor(summary.totalNetProfit),
    },
    {
      title: "期望值",
      value: `${formatCurrency(summary.expectancy)} USDT`,
      subtitle: "每筆交易預期收益",
      color: pnlColor(summary.expectancy),
    },
    {
      title: "最大回撤",
      value: `${summary.maxDrawdown.toFixed(2)} USDT`,
      subtitle: `${summary.maxDrawdownPercent.toFixed(2)}% / ${summary.maxDrawdownDays} 天`,
      color: summary.maxDrawdown < 0 ? "text-red-500" : "text-muted-foreground",
    },
    {
      title: "風險報酬比",
      value: summary.riskRewardRatio.toFixed(2),
      subtitle: "平均盈利 / 平均虧損",
      color: summary.riskRewardRatio >= 1 ? "text-emerald-500" : "text-red-500",
    },
  ];

  const extraStats = [
    { label: "連續獲利", value: `${summary.maxConsecutiveWins} 次` },
    { label: "連續虧損", value: `${summary.maxConsecutiveLosses} 次` },
    { label: "平均持倉時間", value: `${summary.avgHoldingHours.toFixed(1)} 小時` },
    { label: "平均獲利", value: `${formatCurrency(summary.avgWin)} USDT` },
    { label: "平均虧損", value: `${formatCurrency(summary.avgLoss)} USDT` },
    { label: "最大單筆獲利", value: `${formatCurrency(summary.maxWin)} USDT` },
    { label: "最大單筆虧損", value: `${formatCurrency(summary.maxLoss)} USDT` },
  ];

  return (
    <div className="space-y-4">
      {/* Main metric cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
        {mainCards.map((card) => (
          <Card key={card.title}>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">
                {card.title}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className={`text-2xl font-bold ${card.color}`}>
                {card.value}
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                {card.subtitle}
              </p>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Extra stats row */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-7">
        {extraStats.map((stat) => (
          <div
            key={stat.label}
            className="rounded-lg border bg-card px-3 py-2"
          >
            <p className="text-xs text-muted-foreground">{stat.label}</p>
            <p className="text-sm font-semibold">{stat.value}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
