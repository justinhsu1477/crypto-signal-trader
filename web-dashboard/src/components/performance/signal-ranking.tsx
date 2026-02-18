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
import type { SignalSourceStats } from "@/types";

interface SignalRankingProps {
  data: SignalSourceStats[];
}

export function SignalRanking({ data }: SignalRankingProps) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>訊號來源排名</CardTitle>
      </CardHeader>
      <CardContent>
        {data.length === 0 ? (
          <p className="text-center text-sm text-muted-foreground">
            無訊號來源資料
          </p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>來源</TableHead>
                <TableHead className="text-right">交易數</TableHead>
                <TableHead className="text-right">勝率</TableHead>
                <TableHead className="text-right">淨利潤</TableHead>
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
