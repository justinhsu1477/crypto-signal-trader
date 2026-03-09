"use client";

import { useEffect, useState } from "react";
import type { TradeEvent, TradeRecord } from "@/types";
import { getTradeEvents } from "@/lib/api";
import { formatDateTime, formatCurrency, pnlColor } from "@/lib/utils";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { AiConfidenceBadge } from "@/components/ui/ai-confidence-badge";
import { useT } from "@/lib/i18n/i18n-context";

interface TradeDetailProps {
  tradeId: string;
  trade?: TradeRecord;
  onClose: () => void;
}

const EVENT_TYPE_COLORS: Record<string, string> = {
  ENTRY_PLACED: "bg-emerald-500/15 text-emerald-500 border-emerald-500/25",
  CLOSE_PLACED: "bg-blue-500/15 text-blue-500 border-blue-500/25",
  SL_PLACED: "bg-amber-500/15 text-amber-500 border-amber-500/25",
  DCA_ENTRY: "bg-violet-500/15 text-violet-500 border-violet-500/25",
  MOVE_SL: "bg-amber-500/15 text-amber-500 border-amber-500/25",
  CANCEL: "bg-gray-500/15 text-gray-500 border-gray-500/25",
  FAIL_SAFE: "bg-red-500/15 text-red-500 border-red-500/25",
  STREAM_CLOSE: "bg-blue-500/15 text-blue-500 border-blue-500/25",
  SL_LOST: "bg-red-500/15 text-red-500 border-red-500/25",
  TP_LOST: "bg-red-500/15 text-red-500 border-red-500/25",
};

function eventTypeBadge(eventType: string) {
  const colorClass = EVENT_TYPE_COLORS[eventType] ?? "bg-gray-500/15 text-gray-500 border-gray-500/25";
  return <Badge className={colorClass}>{eventType}</Badge>;
}

function FeeRow({ label, value, colorClass }: { label: string; value: number | null | undefined; colorClass?: string }) {
  return (
    <div className="flex justify-between text-sm">
      <span className="text-muted-foreground">{label}</span>
      <span className={colorClass ?? "text-muted-foreground"}>
        {value != null ? formatCurrency(value) : "\u2014"}
      </span>
    </div>
  );
}

export function TradeDetail({ tradeId, trade, onClose }: TradeDetailProps) {
  const { t } = useT();
  const [events, setEvents] = useState<TradeEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function fetchEvents() {
      setLoading(true);
      setError(null);
      try {
        const data = await getTradeEvents(tradeId);
        if (!cancelled) {
          setEvents(data);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : t("common.loadFailed"));
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    fetchEvents();
    return () => {
      cancelled = true;
    };
  }, [tradeId, t]);

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("trades.tradeEventDetail")}</DialogTitle>
          <DialogDescription className="flex items-center gap-2">
            Trade ID: {tradeId}
            {trade && <AiConfidenceBadge confidence={trade.aiConfidence} reasoning={trade.aiReasoning} />}
            {trade?.leverage != null && (
              <Badge variant="outline" className="text-xs">{trade.leverage}x</Badge>
            )}
          </DialogDescription>
        </DialogHeader>

        {/* Fee Breakdown */}
        {trade && (trade.grossProfit != null || trade.totalCommission != null) && (
          <div className="space-y-2 rounded-lg border p-4">
            <h4 className="text-sm font-medium">{t("trades.feeBreakdown")}</h4>
            <FeeRow label={t("trades.grossProfit")} value={trade.grossProfit} colorClass={pnlColor(trade.grossProfit)} />
            <FeeRow label={t("trades.entryFee")} value={trade.entryCommission != null ? -trade.entryCommission : null} />
            <FeeRow label={t("trades.exitFee")} value={trade.exitCommission != null ? -trade.exitCommission : null} />
            <Separator />
            <div className="flex justify-between text-sm font-medium">
              <span>{t("trades.netPnl")}</span>
              <span className={pnlColor(trade.netProfit)}>{formatCurrency(trade.netProfit)}</span>
            </div>
            <div className="flex justify-between text-xs text-muted-foreground">
              <span>{t("trades.totalFee")}</span>
              <span>{trade.totalCommission != null ? formatCurrency(-trade.totalCommission) : "\u2014"}</span>
            </div>
          </div>
        )}

        {loading && (
          <div className="flex items-center justify-center py-12">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
          </div>
        )}

        {error && (
          <div className="flex flex-col items-center gap-3 py-8">
            <p className="text-sm text-red-500">{error}</p>
            <Button variant="outline" size="sm" onClick={() => {
              setError(null);
              setLoading(true);
              getTradeEvents(tradeId).then((data) => setEvents(data)).catch((err) => {
                setError(err instanceof Error ? err.message : t("common.loadFailed"));
              }).finally(() => setLoading(false));
            }}>
              {t("common.retry") ?? "Retry"}
            </Button>
          </div>
        )}

        {!loading && !error && events.length === 0 && (
          <div className="text-center py-8 text-muted-foreground">
            {t("trades.noEvents")}
          </div>
        )}

        {!loading && !error && events.length > 0 && (
          <div className="relative pl-6 border-l-2 border-border space-y-6">
            {events.map((event) => (
              <div key={event.id} className="relative">
                {/* Timeline dot */}
                <div className="absolute -left-[25px] top-1 h-3 w-3 rounded-full bg-border border-2 border-background" />

                <div className="space-y-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    {eventTypeBadge(event.eventType)}
                    <span className="text-xs text-muted-foreground">
                      {formatDateTime(event.timestamp)}
                    </span>
                  </div>

                  <div className="text-sm space-y-0.5">
                    {event.price != null && (
                      <p>
                        <span className="text-muted-foreground">{t("trades.price")}:</span>{" "}
                        {event.price.toLocaleString("en-US", { minimumFractionDigits: 2 })}
                      </p>
                    )}
                    {event.quantity != null && (
                      <p>
                        <span className="text-muted-foreground">{t("trades.quantity")}:</span>{" "}
                        {event.quantity.toLocaleString("en-US")}
                      </p>
                    )}
                    {event.orderSide && (
                      <p>
                        <span className="text-muted-foreground">{t("trades.side")}:</span>{" "}
                        {event.orderSide}
                      </p>
                    )}
                    {event.orderType && (
                      <p>
                        <span className="text-muted-foreground">{t("trades.orderType")}:</span>{" "}
                        {event.orderType}
                      </p>
                    )}
                    {event.binanceOrderId && (
                      <p>
                        <span className="text-muted-foreground">{t("trades.orderId")}:</span>{" "}
                        <span className="font-mono text-xs">{event.binanceOrderId}</span>
                      </p>
                    )}
                    {event.detail && (
                      <p>
                        <span className="text-muted-foreground">{t("trades.detail")}:</span>{" "}
                        {event.detail}
                      </p>
                    )}
                    {event.success ? (
                      <Badge className="bg-emerald-500/15 text-emerald-500 border-emerald-500/25 text-xs mt-1">
                        {t("trades.success")}
                      </Badge>
                    ) : (
                      <div className="mt-1">
                        <Badge className="bg-red-500/15 text-red-500 border-red-500/25 text-xs">
                          {t("trades.error")}
                        </Badge>
                        {event.errorMessage && (
                          <p className="text-xs text-red-500 mt-1">
                            {event.errorMessage}
                          </p>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
