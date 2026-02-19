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
import type { SymbolStats as SymbolStatsType } from "@/types";

interface SymbolStatsProps {
  data: SymbolStatsType[];
}

export function SymbolStats({ data }: SymbolStatsProps) {
  const { t } = useT();

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("performance.symbolPerformance")}</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("performance.symbol")}</TableHead>
              <TableHead className="text-right">{t("performance.tradeCount")}</TableHead>
              <TableHead className="text-right">{t("performance.wins")}</TableHead>
              <TableHead className="text-right">{t("performance.winRate")}</TableHead>
              <TableHead className="text-right">{t("performance.netProfit")}</TableHead>
              <TableHead className="text-right">{t("performance.avgProfit")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.map((row) => (
              <TableRow key={row.symbol}>
                <TableCell className="font-medium">{row.symbol}</TableCell>
                <TableCell className="text-right">{row.trades}</TableCell>
                <TableCell className="text-right">{row.wins}</TableCell>
                <TableCell className="text-right">
                  {formatPercent(row.winRate)}
                </TableCell>
                <TableCell className={`text-right ${pnlColor(row.netProfit)}`}>
                  {formatCurrency(row.netProfit)}
                </TableCell>
                <TableCell className={`text-right ${pnlColor(row.avgProfit)}`}>
                  {formatCurrency(row.avgProfit)}
                </TableCell>
              </TableRow>
            ))}
            {data.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} className="text-center text-muted-foreground">
                  {t("common.noData")}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
