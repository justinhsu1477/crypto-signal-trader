"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatCurrency, formatPercent, pnlColor } from "@/lib/utils";
import { useT } from "@/lib/i18n/i18n-context";
import type { SignalSourceStats } from "@/types";

interface SignalRankingProps {
  data: SignalSourceStats[];
}

export function SignalRanking({ data }: SignalRankingProps) {
  const { t } = useT();

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("performance.signalSourceRanking")}</CardTitle>
      </CardHeader>
      <CardContent>
        {data.length === 0 ? (
          <p className="text-center text-sm text-muted-foreground">
            {t("performance.noSignalData")}
          </p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("performance.source")}</TableHead>
                <TableHead className="text-right">{t("performance.tradeCount")}</TableHead>
                <TableHead className="text-right">{t("performance.winRate")}</TableHead>
                <TableHead className="text-right">{t("performance.netProfit")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((row) => (
                <TableRow key={row.source}>
                  <TableCell className="font-medium">{row.source}</TableCell>
                  <TableCell className="text-right">{row.trades}</TableCell>
                  <TableCell className="text-right">
                    {formatPercent(row.winRate)}
                  </TableCell>
                  <TableCell className={`text-right ${pnlColor(row.netProfit)}`}>
                    {formatCurrency(row.netProfit)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}
