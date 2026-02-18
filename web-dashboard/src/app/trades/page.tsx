"use client";

import { useEffect, useState } from "react";
import type { TradeHistoryResponse } from "@/types";
import { getTradeHistory } from "@/lib/api";
import { TradeTable } from "@/components/trades/trade-table";
import { TradeDetail } from "@/components/trades/trade-detail";

export default function TradesPage() {
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [response, setResponse] = useState<TradeHistoryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedTradeId, setSelectedTradeId] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function fetchTrades() {
      setLoading(true);
      setError(null);
      try {
        const data = await getTradeHistory(page, size);
        if (!cancelled) {
          setResponse(data);
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

    fetchTrades();
    return () => {
      cancelled = true;
    };
  }, [page, size]);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">交易紀錄</h1>

      {loading && (
        <div className="flex items-center justify-center py-20">
          <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-primary" />
        </div>
      )}

      {error && (
        <div className="text-center py-12 text-red-500">{error}</div>
      )}

      {!loading && !error && response && (
        <TradeTable
          trades={response.trades}
          pagination={response.pagination}
          onPageChange={setPage}
          onSelect={setSelectedTradeId}
        />
      )}

      {selectedTradeId && (
        <TradeDetail
          tradeId={selectedTradeId}
          onClose={() => setSelectedTradeId(null)}
        />
      )}
    </div>
  );
}
