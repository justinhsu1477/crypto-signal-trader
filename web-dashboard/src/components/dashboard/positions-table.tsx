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
import { Badge } from "@/components/ui/badge";
import { formatAmount, formatDateTime } from "@/lib/utils";
import type { OpenPositionSummary } from "@/types";

interface PositionsTableProps {
  positions: OpenPositionSummary[];
}

export function PositionsTable({ positions }: PositionsTableProps) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">當前持倉</CardTitle>
      </CardHeader>
      <CardContent>
        {positions.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">
            目前無持倉
          </p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Symbol</TableHead>
                <TableHead>Side</TableHead>
                <TableHead className="text-right">Entry Price</TableHead>
                <TableHead className="text-right">Stop Loss</TableHead>
                <TableHead className="text-center">DCA Count</TableHead>
                <TableHead>Signal Source</TableHead>
                <TableHead>Entry Time</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {positions.map((pos, idx) => (
                <TableRow key={`${pos.symbol}-${pos.side}-${idx}`}>
                  <TableCell className="font-medium">{pos.symbol}</TableCell>
                  <TableCell>
                    <Badge
                      className={
                        pos.side === "LONG"
                          ? "bg-emerald-500/15 text-emerald-500 border-emerald-500/20"
                          : "bg-red-500/15 text-red-500 border-red-500/20"
                      }
                      variant="outline"
                    >
                      {pos.side}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-right">
                    {formatAmount(pos.entryPrice)}
                  </TableCell>
                  <TableCell className="text-right">
                    {formatAmount(pos.stopLoss)}
                  </TableCell>
                  <TableCell className="text-center">
                    {pos.dcaCount ?? "—"}
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {pos.signalSource ?? "—"}
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {formatDateTime(pos.entryTime)}
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
