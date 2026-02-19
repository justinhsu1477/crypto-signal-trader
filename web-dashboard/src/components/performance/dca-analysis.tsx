"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCurrency, formatPercent } from "@/lib/utils";
import { useT } from "@/lib/i18n/i18n-context";
import type { DcaAnalysis as DcaAnalysisType } from "@/types";

interface DcaAnalysisProps {
  data: DcaAnalysisType;
}

export function DcaAnalysis({ data }: DcaAnalysisProps) {
  const { t } = useT();
  const noDcaBetter = data.noDcaAvgProfit >= data.dcaAvgProfit;

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("performance.dcaAnalysis")}</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-2 gap-4">
          {/* No DCA */}
          <div
            className={`space-y-3 rounded-lg border p-4 ${
              noDcaBetter
                ? "border-emerald-500/30 bg-emerald-500/5"
                : "border-border"
            }`}
          >
            <h4 className="text-sm font-semibold">
              {t("performance.noDca")}
              {noDcaBetter && (
                <span className="ml-2 text-xs text-emerald-500">{t("performance.better")}</span>
              )}
            </h4>
            <div className="space-y-2">
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">{t("performance.tradeCount")}</span>
                <span className="font-medium">{data.noDcaTrades}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">{t("performance.winRate")}</span>
                <span className="font-medium">
                  {formatPercent(data.noDcaWinRate)}
                </span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">{t("performance.avgProfit")}</span>
                <span
                  className={`font-medium ${
                    data.noDcaAvgProfit >= 0
                      ? "text-emerald-500"
                      : "text-red-500"
                  }`}
                >
                  {formatCurrency(data.noDcaAvgProfit)} USDT
                </span>
              </div>
            </div>
          </div>

          {/* With DCA */}
          <div
            className={`space-y-3 rounded-lg border p-4 ${
              !noDcaBetter
                ? "border-emerald-500/30 bg-emerald-500/5"
                : "border-border"
            }`}
          >
            <h4 className="text-sm font-semibold">
              {t("performance.withDca")}
              {!noDcaBetter && (
                <span className="ml-2 text-xs text-emerald-500">{t("performance.better")}</span>
              )}
            </h4>
            <div className="space-y-2">
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">{t("performance.tradeCount")}</span>
                <span className="font-medium">{data.dcaTrades}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">{t("performance.winRate")}</span>
                <span className="font-medium">
                  {formatPercent(data.dcaWinRate)}
                </span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">{t("performance.avgProfit")}</span>
                <span
                  className={`font-medium ${
                    data.dcaAvgProfit >= 0
                      ? "text-emerald-500"
                      : "text-red-500"
                  }`}
                >
                  {formatCurrency(data.dcaAvgProfit)} USDT
                </span>
              </div>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
