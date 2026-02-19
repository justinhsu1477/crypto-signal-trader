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
import { useT } from "@/lib/i18n/i18n-context";
import type { OpenPositionSummary } from "@/types";

interface PositionsTableProps {
  positions: OpenPositionSummary[];
}

export function PositionsTable({ positions }: PositionsTableProps) {
  const { t } = useT();

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t("dashboard.currentPositions")}</CardTitle>
      </CardHeader>
      <CardContent>
        {positions.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">
            {t("dashboard.noPositions")}
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
                    {pos.dcaCount ?? "\u2014"}
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {pos.signalSource ?? "\u2014"}
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
