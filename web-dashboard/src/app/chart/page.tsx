"use client";

import { useEffect, useState, useCallback } from "react";
import { getDashboardOverview } from "@/lib/api";
import { TradingViewChart } from "@/components/dashboard/tradingview-chart";
import { useT } from "@/lib/i18n/i18n-context";
import type { DashboardOverview } from "@/types";

export default function ChartPage() {
  const { t } = useT();
  const [data, setData] = useState<DashboardOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    try {
      const overview = await getDashboardOverview();
      setData(overview);
    } catch (err) {
      setError(err instanceof Error ? err.message : t("common.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  if (loading) {
    return (
      <div className="flex h-[50vh] items-center justify-center">
        <p className="text-muted-foreground">{t("common.loading")}</p>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="flex h-[50vh] items-center justify-center">
        <p className="text-red-500">{error ?? t("common.cannotLoad")}</p>
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6">
      <h1 className="text-2xl font-bold tracking-tight">{t("nav.chart")}</h1>
      <TradingViewChart positions={data.positions} />
    </div>
  );
}
