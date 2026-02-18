"use client";

import { useEffect, useState } from "react";
import type { TradeEvent } from "@/types";
import { getTradeEvents } from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { Badge } from "@/components/ui/badge";

interface TradeDetailProps {
  tradeId: string;
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

export function TradeDetail({ tradeId, onClose }: TradeDetailProps) {
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
          setError(err instanceof Error ? err.message : "載入失敗");
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
  }, [tradeId]);

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>交易事件明細</DialogTitle>
          <DialogDescription>Trade ID: {tradeId}</DialogDescription>
        </DialogHeader>

        {loading && (
          <div className="flex items-center justify-center py-12">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
          </div>
        )}

        {error && (
          <div className="text-center py-8 text-red-500">{error}</div>
        )}

        {!loading && !error && events.length === 0 && (
          <div className="text-center py-8 text-muted-foreground">
            沒有事件紀錄
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
                        <span className="text-muted-foreground">Price:</span>{" "}
                        {event.price.toLocaleString("en-US", { minimumFractionDigits: 2 })}
                      </p>
                    )}
                    {event.quantity != null && (
                      <p>
                        <span className="text-muted-foreground">Quantity:</span>{" "}
                        {event.quantity.toLocaleString("en-US")}
                      </p>
                    )}
                    {event.orderSide && (
                      <p>
                        <span className="text-muted-foreground">Side:</span>{" "}
                        {event.orderSide}
                      </p>
                    )}
                    {event.orderType && (
                      <p>
                        <span className="text-muted-foreground">Order Type:</span>{" "}
                        {event.orderType}
                      </p>
                    )}
                    {event.binanceOrderId && (
                      <p>
                        <span className="text-muted-foreground">Order ID:</span>{" "}
                        <span className="font-mono text-xs">{event.binanceOrderId}</span>
                      </p>
                    )}
                    {event.detail && (
                      <p>
                        <span className="text-muted-foreground">Detail:</span>{" "}
                        {event.detail}
                      </p>
                    )}
                    {event.success ? (
                      <Badge className="bg-emerald-500/15 text-emerald-500 border-emerald-500/25 text-xs mt-1">
                        Success
                      </Badge>
                    ) : (
                      <div className="mt-1">
                        <Badge className="bg-red-500/15 text-red-500 border-red-500/25 text-xs">
                          Error
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
