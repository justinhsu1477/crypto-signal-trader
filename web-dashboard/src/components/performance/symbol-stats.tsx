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
import type { SymbolStats as SymbolStatsType } from "@/types";

interface SymbolStatsProps {
  data: SymbolStatsType[];
}

export function SymbolStats({ data }: SymbolStatsProps) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>幣種績效</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>幣種</TableHead>
              <TableHead className="text-right">交易數</TableHead>
              <TableHead className="text-right">勝場</TableHead>
              <TableHead className="text-right">勝率</TableHead>
              <TableHead className="text-right">淨利潤</TableHead>
              <TableHead className="text-right">平均利潤</TableHead>
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
                  無資料
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
