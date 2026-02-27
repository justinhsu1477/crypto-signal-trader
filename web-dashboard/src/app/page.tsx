"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { getDashboardOverview } from "@/lib/api";
import { KpiCards } from "@/components/dashboard/kpi-cards";
import { RiskBudgetCard } from "@/components/dashboard/risk-budget";
import { PositionsTable } from "@/components/dashboard/positions-table";
import { SystemStatus } from "@/components/dashboard/system-status";
import { OnboardingChecklist } from "@/components/dashboard/onboarding-checklist";
import { ErrorBoundary } from "@/components/ui/error-boundary";
import { useT } from "@/lib/i18n/i18n-context";
import { useAuth } from "@/lib/auth-context";
import type { DashboardOverview } from "@/types";

export default function HomePage() {
  const { t } = useT();
  const { role } = useAuth();
  const router = useRouter();
  const [data, setData] = useState<DashboardOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // ADMIN users → redirect to admin dashboard
  useEffect(() => {
    if (role === "ADMIN") {
      router.replace("/admin");
    }
  }, [role, router]);

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
  }, [t]);

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
      <ErrorBoundary>
        <OnboardingChecklist data={data} />
      </ErrorBoundary>
      <ErrorBoundary>
        <KpiCards data={data} />
      </ErrorBoundary>
      <ErrorBoundary>
        <div className="grid gap-4 md:grid-cols-2">
          <RiskBudgetCard data={data.riskBudget} />
          <SystemStatus circuitBreakerActive={data.riskBudget.circuitBreakerActive} />
        </div>
      </ErrorBoundary>
      <ErrorBoundary>
        <PositionsTable positions={data.positions} />
      </ErrorBoundary>
    </div>
  );
}
