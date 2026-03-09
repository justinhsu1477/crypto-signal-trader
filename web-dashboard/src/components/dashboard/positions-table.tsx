"use client";

import { useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatAmount, formatCurrency, formatDateTime, pnlColor } from "@/lib/utils";
import { useT } from "@/lib/i18n/i18n-context";
import { AiConfidenceBadge } from "@/components/ui/ai-confidence-badge";
import { closePosition, cancelOrders } from "@/lib/api";
import { toast } from "sonner";
import { X, Ban, Loader2 } from "lucide-react";
import type { OpenPositionSummary } from "@/types";

interface PositionsTableProps {
  positions: OpenPositionSummary[];
  onRefresh?: () => void;
}

type ActionType = "close" | "cancel";

interface PendingAction {
  type: ActionType;
  symbol: string;
  side: string;
}

export function PositionsTable({ positions, onRefresh }: PositionsTableProps) {
  const { t } = useT();
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
  const [actionLoading, setActionLoading] = useState(false);

  async function handleConfirm() {
    if (!pendingAction) return;
    setActionLoading(true);
    try {
      if (pendingAction.type === "close") {
        await closePosition(pendingAction.symbol, pendingAction.side);
        toast.success(t("dashboard.positionClosed"));
      } else {
        await cancelOrders(pendingAction.symbol);
        toast.success(t("dashboard.ordersCancelled"));
      }
      setPendingAction(null);
      onRefresh?.();
    } catch (err) {
      toast.error(
        `${t("dashboard.actionFailed")}: ${err instanceof Error ? err.message : "Unknown error"}`
      );
    } finally {
      setActionLoading(false);
    }
  }

  return (
    <>
      <Card data-tutorial-step="positions-table">
        <CardHeader>
          <CardTitle className="text-base">{t("dashboard.currentPositions")}</CardTitle>
        </CardHeader>
        <CardContent>
          {positions.length === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">
              {t("dashboard.noPositions")}
            </p>
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Symbol</TableHead>
                    <TableHead>Side</TableHead>
                    <TableHead className="text-right">Entry Price</TableHead>
                    <TableHead className="text-right">{t("dashboard.markPrice")}</TableHead>
                    <TableHead className="text-right">{t("dashboard.unrealizedPnl")}</TableHead>
                    <TableHead className="text-right">{t("dashboard.positionValue")}</TableHead>
                    <TableHead className="text-right">Stop Loss</TableHead>
                    <TableHead className="text-center">DCA</TableHead>
                    <TableHead className="text-center">{t("dashboard.aiScore")}</TableHead>
                    <TableHead>Entry Time</TableHead>
                    <TableHead className="text-center">{t("dashboard.actions")}</TableHead>
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
                        {pos.markPrice != null ? formatAmount(pos.markPrice) : "\u2014"}
                      </TableCell>
                      <TableCell className={`text-right font-medium ${pnlColor(pos.unrealizedPnl)}`}>
                        {pos.unrealizedPnl != null ? formatCurrency(pos.unrealizedPnl) : "\u2014"}
                      </TableCell>
                      <TableCell className="text-right">
                        {pos.positionValue != null ? formatCurrency(pos.positionValue) : "\u2014"}
                      </TableCell>
                      <TableCell className="text-right">
                        {formatAmount(pos.stopLoss)}
                      </TableCell>
                      <TableCell className="text-center">
                        {pos.dcaCount ?? "\u2014"}
                      </TableCell>
                      <TableCell className="text-center">
                        <AiConfidenceBadge confidence={pos.aiConfidence} reasoning={pos.aiReasoning} />
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {formatDateTime(pos.entryTime)}
                      </TableCell>
                      <TableCell className="text-center">
                        <div className="flex items-center justify-center gap-1">
                          <Button
                            variant="ghost"
                            size="icon"
                            className="h-7 w-7 text-red-500 hover:text-red-600 hover:bg-red-500/10"
                            title={t("dashboard.closePosition")}
                            onClick={() =>
                              setPendingAction({ type: "close", symbol: pos.symbol, side: pos.side })
                            }
                          >
                            <X className="h-4 w-4" />
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            className="h-7 w-7 text-amber-500 hover:text-amber-600 hover:bg-amber-500/10"
                            title={t("dashboard.cancelOrders")}
                            onClick={() =>
                              setPendingAction({ type: "cancel", symbol: pos.symbol, side: pos.side })
                            }
                          >
                            <Ban className="h-4 w-4" />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Confirmation Dialog */}
      <Dialog open={!!pendingAction} onOpenChange={(open) => !open && setPendingAction(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {pendingAction?.type === "close"
                ? t("dashboard.closeConfirmTitle")
                : t("dashboard.cancelConfirmTitle")}
            </DialogTitle>
            <DialogDescription>
              {pendingAction?.type === "close"
                ? t("dashboard.closeConfirmDesc", {
                    symbol: pendingAction.symbol,
                    side: pendingAction.side,
                  })
                : t("dashboard.cancelConfirmDesc", { symbol: pendingAction?.symbol ?? "" })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setPendingAction(null)}
              disabled={actionLoading}
            >
              {t("common.cancel")}
            </Button>
            <Button
              variant="destructive"
              onClick={handleConfirm}
              disabled={actionLoading}
            >
              {actionLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              {t("common.confirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
