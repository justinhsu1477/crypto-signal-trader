"use client";

import { useEffect, useState, useCallback } from "react";
import type { TradeHistoryResponse, TradeRecord } from "@/types";
import { getTradeHistory, exportTrades } from "@/lib/api";
import { TradeTable } from "@/components/trades/trade-table";
import { TradeDetail } from "@/components/trades/trade-detail";
import { useT } from "@/lib/i18n/i18n-context";
import { Download } from "lucide-react";
import { toast } from "sonner";

export default function TradesPage() {
  const { t } = useT();
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [response, setResponse] = useState<TradeHistoryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedTrade, setSelectedTrade] = useState<TradeRecord | null>(null);
  const [exporting, setExporting] = useState(false);

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
          setError(err instanceof Error ? err.message : t("common.loadFailed"));
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
  }, [page, size, t]);

  const handleExport = useCallback(async () => {
    setExporting(true);
    try {
      await exportTrades(30);
    } catch {
      toast.error(t("trades.exportFailed"));
    } finally {
      setExporting(false);
    }
  }, [t]);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t("trades.title")}</h1>
        <button
          onClick={handleExport}
          disabled={exporting}
          className="inline-flex items-center gap-2 rounded-md border border-border bg-background px-3 py-2 text-sm font-medium hover:bg-accent hover:text-accent-foreground disabled:opacity-50 disabled:pointer-events-none transition-colors"
        >
          <Download className="h-4 w-4" />
          {exporting ? t("trades.exporting") : t("trades.exportCsv")}
        </button>
      </div>

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
          onSelect={setSelectedTrade}
        />
      )}

      {selectedTrade && (
        <TradeDetail
          tradeId={selectedTrade.tradeId}
          trade={selectedTrade}
          onClose={() => setSelectedTrade(null)}
        />
      )}
    </div>
  );
}
