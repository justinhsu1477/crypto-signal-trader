"use client";

import { Wallet, TrendingUp, DollarSign, Activity } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatAmount, formatCurrency, pnlColor } from "@/lib/utils";
import type { DashboardOverview } from "@/types";

interface KpiCardsProps {
  data: DashboardOverview;
}

export function KpiCards({ data }: KpiCardsProps) {
  const { account } = data;

  const cards = [
    {
      title: "Available Balance",
      value: `${formatAmount(account.availableBalance)} USDT`,
      icon: Wallet,
      color: "text-blue-500",
    },
    {
      title: "Open Positions",
      value: account.openPositionCount.toString(),
      icon: Activity,
      color: "text-violet-500",
    },
    {
      title: "Today P&L",
      value: `${formatCurrency(account.todayPnl)} USDT`,
      icon: DollarSign,
      color: pnlColor(account.todayPnl),
    },
    {
      title: "Today Trades",
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
          <Card key={card.title}>
            <CardHeader className="flex flex-row items-center justify-between pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">
                {card.title}
              </CardTitle>
              <Icon className={`h-4 w-4 ${card.color}`} />
            </CardHeader>
            <CardContent>
              <div className={`text-2xl font-bold ${card.title === "Today P&L" ? card.color : ""}`}>
                {card.value}
              </div>
            </CardContent>
          </Card>
        );
      })}
    </div>
  );
}
