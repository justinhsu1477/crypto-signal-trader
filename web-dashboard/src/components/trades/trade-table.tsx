"use client";

import type { TradeRecord, Pagination } from "@/types";
import { formatCurrency, formatDateTime, pnlColor } from "@/lib/utils";
import {
  Table,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
} from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useT } from "@/lib/i18n/i18n-context";

interface TradeTableProps {
  trades: TradeRecord[];
  pagination: Pagination;
  onPageChange: (page: number) => void;
  onSelect: (tradeId: string) => void;
}

function sideBadge(side: string) {
  if (side === "LONG") {
    return (
      <Badge className="bg-emerald-500/15 text-emerald-500 border-emerald-500/25">
        {side}
      </Badge>
    );
  }
  return (
    <Badge className="bg-red-500/15 text-red-500 border-red-500/25">
      {side}
    </Badge>
  );
}

function statusBadge(status: string) {
  switch (status) {
    case "OPEN":
      return (
        <Badge className="bg-blue-500/15 text-blue-500 border-blue-500/25">
          {status}
        </Badge>
      );
    case "CLOSED":
      return <Badge variant="default">{status}</Badge>;
    case "CANCELLED":
      return <Badge variant="secondary">{status}</Badge>;
    default:
      return <Badge variant="outline">{status}</Badge>;
  }
}

export function TradeTable({
  trades,
  pagination,
  onPageChange,
  onSelect,
}: TradeTableProps) {
  const { t } = useT();
  const { page, totalPages, totalElements } = pagination;

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("trades.title")}</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Symbol</TableHead>
              <TableHead>Side</TableHead>
              <TableHead className="text-right">Entry Price</TableHead>
              <TableHead className="text-right">Exit Price</TableHead>
              <TableHead className="text-right">Quantity</TableHead>
              <TableHead className="text-right">Net P&L</TableHead>
              <TableHead>Exit Reason</TableHead>
              <TableHead>Signal Source</TableHead>
              <TableHead className="text-center">DCA</TableHead>
              <TableHead>Entry Time</TableHead>
              <TableHead>Exit Time</TableHead>
              <TableHead>Status</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {trades.length === 0 ? (
              <TableRow>
                <TableCell colSpan={12} className="text-center text-muted-foreground py-8">
                  {t("trades.noTrades")}
                </TableCell>
              </TableRow>
            ) : (
              trades.map((trade) => (
                <TableRow
                  key={trade.tradeId}
                  className="cursor-pointer"
                  onClick={() => onSelect(trade.tradeId)}
                >
                  <TableCell className="font-medium">{trade.symbol}</TableCell>
                  <TableCell>{sideBadge(trade.side)}</TableCell>
                  <TableCell className="text-right">
                    {trade.entryPrice?.toLocaleString("en-US", { minimumFractionDigits: 2 }) ?? "\u2014"}
                  </TableCell>
                  <TableCell className="text-right">
                    {trade.exitPrice?.toLocaleString("en-US", { minimumFractionDigits: 2 }) ?? "\u2014"}
                  </TableCell>
                  <TableCell className="text-right">
                    {trade.entryQuantity?.toLocaleString("en-US") ?? "\u2014"}
                  </TableCell>
                  <TableCell className={`text-right font-medium ${pnlColor(trade.netProfit)}`}>
                    {formatCurrency(trade.netProfit)}
                  </TableCell>
                  <TableCell>{trade.exitReason ?? "\u2014"}</TableCell>
                  <TableCell>{trade.signalSource ?? "\u2014"}</TableCell>
                  <TableCell className="text-center">{trade.dcaCount ?? 0}</TableCell>
                  <TableCell>{formatDateTime(trade.entryTime)}</TableCell>
                  <TableCell>{formatDateTime(trade.exitTime)}</TableCell>
                  <TableCell>{statusBadge(trade.status)}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>

        {/* Pagination */}
        <div className="flex items-center justify-between mt-4">
          <p className="text-sm text-muted-foreground">
            {t("trades.pagination", { current: page + 1, total: totalPages, count: totalElements })}
          </p>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page <= 0}
              onClick={() => onPageChange(page - 1)}
            >
              {t("common.previous")}
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page >= totalPages - 1}
              onClick={() => onPageChange(page + 1)}
            >
              {t("common.next")}
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
