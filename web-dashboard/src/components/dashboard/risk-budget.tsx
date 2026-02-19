"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Badge } from "@/components/ui/badge";
import { formatAmount } from "@/lib/utils";
import { useT } from "@/lib/i18n/i18n-context";
import type { RiskBudget } from "@/types";

interface RiskBudgetProps {
  data: RiskBudget;
}

export function RiskBudgetCard({ data }: RiskBudgetProps) {
  const { t } = useT();
  const { dailyLossLimit, todayLossUsed, remainingBudget, circuitBreakerActive } = data;

  const usedPercent =
    dailyLossLimit > 0
      ? Math.min((todayLossUsed / dailyLossLimit) * 100, 100)
      : 0;

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-base">{t("dashboard.riskBudget")}</CardTitle>
        {circuitBreakerActive && (
          <Badge variant="destructive">{t("dashboard.circuitBreaker")}</Badge>
        )}
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">{t("dashboard.lossUsed")}</span>
            <span className="font-medium">
              {formatAmount(todayLossUsed)} / {formatAmount(dailyLossLimit)} USDT
            </span>
          </div>
          <Progress
            value={usedPercent}
            className={circuitBreakerActive ? "[&>div]:bg-red-500" : usedPercent > 80 ? "[&>div]:bg-amber-500" : ""}
          />
        </div>
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">{t("dashboard.remainingBudget")}</span>
          <span className={`font-semibold ${remainingBudget <= 0 ? "text-red-500" : "text-emerald-500"}`}>
            {formatAmount(remainingBudget)} USDT
          </span>
        </div>
      </CardContent>
    </Card>
  );
}
