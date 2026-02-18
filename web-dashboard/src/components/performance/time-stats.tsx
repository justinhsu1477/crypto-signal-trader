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
import type { WeeklyStats, MonthlyStats } from "@/types";

interface TimeStatsProps {
  weeklyStats: WeeklyStats[];
  monthlyStats: MonthlyStats[];
}

export function TimeStats({ weeklyStats, monthlyStats }: TimeStatsProps) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>時間統計</CardTitle>
      </CardHeader>
      <CardContent>
        <Tabs defaultValue="weekly">
          <TabsList>
            <TabsTrigger value="weekly">週統計</TabsTrigger>
            <TabsTrigger value="monthly">月統計</TabsTrigger>
          </TabsList>

          <TabsContent value="weekly">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>期間</TableHead>
                  <TableHead className="text-right">交易數</TableHead>
                  <TableHead className="text-right">勝率</TableHead>
                  <TableHead className="text-right">淨利潤</TableHead>
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
                      無資料
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
                  <TableHead>月份</TableHead>
                  <TableHead className="text-right">交易數</TableHead>
                  <TableHead className="text-right">勝率</TableHead>
                  <TableHead className="text-right">淨利潤</TableHead>
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
                      無資料
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
