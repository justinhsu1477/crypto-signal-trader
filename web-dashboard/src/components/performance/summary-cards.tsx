"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCurrency, formatPercent, pnlColor } from "@/lib/utils";
import { useT } from "@/lib/i18n/i18n-context";
import type { PerformanceSummary } from "@/types";

interface SummaryCardsProps {
  summary: PerformanceSummary;
}

export function SummaryCards({ summary }: SummaryCardsProps) {
  const { t } = useT();

  const mainCards = [
    {
      title: t("performance.winRate"),
      value: formatPercent(summary.winRate),
      subtitle: `${summary.winningTrades}W / ${summary.totalTrades}T`,
      color: summary.winRate >= 50 ? "text-emerald-500" : "text-red-500",
    },
    {
      title: t("performance.profitFactor"),
      value: summary.profitFactor.toFixed(2),
      subtitle: t("performance.profitOverLoss"),
      color: summary.profitFactor >= 1 ? "text-emerald-500" : "text-red-500",
    },
    {
      title: t("performance.netProfit"),
      value: `${formatCurrency(summary.totalNetProfit)} USDT`,
      subtitle: t("performance.commission", { amount: summary.totalCommission.toFixed(2) }),
      color: pnlColor(summary.totalNetProfit),
    },
    {
      title: t("performance.expectancy"),
      value: `${formatCurrency(summary.expectancy)} USDT`,
      subtitle: t("performance.expectancySubtitle"),
      color: pnlColor(summary.expectancy),
    },
    {
      title: t("performance.maxDrawdown"),
      value: `${summary.maxDrawdown.toFixed(2)} USDT`,
      subtitle: t("performance.maxDrawdownSubtitle", { percent: summary.maxDrawdownPercent.toFixed(2), days: summary.maxDrawdownDays }),
      color: summary.maxDrawdown < 0 ? "text-red-500" : "text-muted-foreground",
    },
    {
      title: t("performance.riskRewardRatio"),
      value: summary.riskRewardRatio.toFixed(2),
      subtitle: t("performance.riskRewardSubtitle"),
      color: summary.riskRewardRatio >= 1 ? "text-emerald-500" : "text-red-500",
    },
  ];

  const extraStats = [
    { label: t("performance.consecutiveWins"), value: t("performance.times", { n: summary.maxConsecutiveWins }) },
    { label: t("performance.consecutiveLosses"), value: t("performance.times", { n: summary.maxConsecutiveLosses }) },
    { label: t("performance.avgHoldingTime"), value: t("performance.hours", { n: summary.avgHoldingHours.toFixed(1) }) },
    { label: t("performance.avgWin"), value: `${formatCurrency(summary.avgWin)} USDT` },
    { label: t("performance.avgLoss"), value: `${formatCurrency(summary.avgLoss)} USDT` },
    { label: t("performance.maxWin"), value: `${formatCurrency(summary.maxWin)} USDT` },
    { label: t("performance.maxLoss"), value: `${formatCurrency(summary.maxLoss)} USDT` },
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
