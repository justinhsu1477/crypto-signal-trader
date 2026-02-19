"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
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
import type { WeeklyStats, MonthlyStats } from "@/types";

interface TimeStatsProps {
  weeklyStats: WeeklyStats[];
  monthlyStats: MonthlyStats[];
}

export function TimeStats({ weeklyStats, monthlyStats }: TimeStatsProps) {
  const { t } = useT();

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("performance.timeStats")}</CardTitle>
      </CardHeader>
      <CardContent>
        <Tabs defaultValue="weekly">
          <TabsList>
            <TabsTrigger value="weekly">{t("performance.weeklyStats")}</TabsTrigger>
            <TabsTrigger value="monthly">{t("performance.monthlyStats")}</TabsTrigger>
          </TabsList>

          <TabsContent value="weekly">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("performance.period")}</TableHead>
                  <TableHead className="text-right">{t("performance.tradeCount")}</TableHead>
                  <TableHead className="text-right">{t("performance.winRate")}</TableHead>
                  <TableHead className="text-right">{t("performance.netProfit")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {weeklyStats.map((row) => (
                  <TableRow key={row.weekStart}>
                    <TableCell className="font-medium">
                      {row.weekStart} ~ {row.weekEnd}
                    </TableCell>
                    <TableCell className="text-right">{row.trades}</TableCell>
                    <TableCell className="text-right">
                      {formatPercent(row.winRate)}
                    </TableCell>
                    <TableCell className={`text-right ${pnlColor(row.netProfit)}`}>
                      {formatCurrency(row.netProfit)}
                    </TableCell>
                  </TableRow>
                ))}
                {weeklyStats.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={4} className="text-center text-muted-foreground">
                      {t("common.noData")}
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TabsContent>

          <TabsContent value="monthly">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("performance.month")}</TableHead>
                  <TableHead className="text-right">{t("performance.tradeCount")}</TableHead>
                  <TableHead className="text-right">{t("performance.winRate")}</TableHead>
                  <TableHead className="text-right">{t("performance.netProfit")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {monthlyStats.map((row) => (
                  <TableRow key={row.month}>
                    <TableCell className="font-medium">{row.month}</TableCell>
                    <TableCell className="text-right">{row.trades}</TableCell>
                    <TableCell className="text-right">
                      {formatPercent(row.winRate)}
                    </TableCell>
                    <TableCell className={`text-right ${pnlColor(row.netProfit)}`}>
                      {formatCurrency(row.netProfit)}
                    </TableCell>
                  </TableRow>
                ))}
                {monthlyStats.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={4} className="text-center text-muted-foreground">
                      {t("common.noData")}
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TabsContent>
        </Tabs>
      </CardContent>
    </Card>
  );
}
