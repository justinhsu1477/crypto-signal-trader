"use client";

import { Wallet, TrendingUp, DollarSign, Activity } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatAmount, formatCurrency, pnlColor } from "@/lib/utils";
import { useT } from "@/lib/i18n/i18n-context";
import type { DashboardOverview } from "@/types";

interface KpiCardsProps {
  data: DashboardOverview;
}

export function KpiCards({ data }: KpiCardsProps) {
  const { t } = useT();
  const { account } = data;

  const todayPnlKey = "todayPnl";

  const cards = [
    {
      key: "availableBalance",
      title: t("dashboard.availableBalance"),
      value: `${formatAmount(account.availableBalance)} USDT`,
      icon: Wallet,
      color: "text-blue-500",
    },
    {
      key: "openPositions",
      title: t("dashboard.openPositions"),
      value: account.openPositionCount.toString(),
      icon: Activity,
      color: "text-violet-500",
    },
    {
      key: todayPnlKey,
      title: t("dashboard.todayPnl"),
      value: `${formatCurrency(account.todayPnl)} USDT`,
      icon: DollarSign,
      color: pnlColor(account.todayPnl),
    },
    {
      key: "todayTrades",
      title: t("dashboard.todayTrades"),
      value: account.todayTradeCount.toString(),
      icon: TrendingUp,
      color: "text-amber-500",
    },
  ];

  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {cards.map((card) => {
        const Icon = card.icon;
        return (
          <Card key={card.key}>
            <CardHeader className="flex flex-row items-center justify-between pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">
                {card.title}
              </CardTitle>
              <Icon className={`h-4 w-4 ${card.color}`} />
            </CardHeader>
            <CardContent>
              <div className={`text-2xl font-bold ${card.key === todayPnlKey ? card.color : ""}`}>
                {card.value}
              </div>
            </CardContent>
          </Card>
        );
      })}
    </div>
  );
}
