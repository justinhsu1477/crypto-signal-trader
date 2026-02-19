"use client";

import { useEffect, useState } from "react";
import { getDashboardOverview } from "@/lib/api";
import { KpiCards } from "@/components/dashboard/kpi-cards";
import { RiskBudgetCard } from "@/components/dashboard/risk-budget";
import { PositionsTable } from "@/components/dashboard/positions-table";
import { SystemStatus } from "@/components/dashboard/system-status";
import { useT } from "@/lib/i18n/i18n-context";
import type { DashboardOverview } from "@/types";

export default function HomePage() {
  const { t } = useT();
  const [data, setData] = useState<DashboardOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function fetchOverview() {
      try {
        const overview = await getDashboardOverview();
        setData(overview);
      } catch (err) {
        setError(err instanceof Error ? err.message : t("common.loadFailed"));
      } finally {
        setLoading(false);
      }
    }
    fetchOverview();
  }, []);

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
      <h1 className="text-2xl font-bold tracking-tight">{t("dashboard.title")}</h1>
      <KpiCards data={data} />
      <div className="grid gap-4 md:grid-cols-2">
        <RiskBudgetCard data={data.riskBudget} />
        <SystemStatus circuitBreakerActive={data.riskBudget.circuitBreakerActive} />
      </div>
      <PositionsTable positions={data.positions} />
    </div>
  );
}
