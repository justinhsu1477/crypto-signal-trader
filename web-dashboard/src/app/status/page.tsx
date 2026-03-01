"use client";

import { useEffect, useState, useCallback } from "react";
import type { PublicStatusResponse, PublicServiceStatus } from "@/types";
import { getPublicSystemStatus } from "@/lib/api";
import { useT } from "@/lib/i18n/i18n-context";
import {
  CheckCircle2,
  AlertTriangle,
  XCircle,
  RefreshCw,
  Database,
  BarChart3,
  Bell,
  Radio,
} from "lucide-react";

const SERVICE_ICONS: Record<string, React.ReactNode> = {
  Database: <Database className="h-5 w-5" />,
  "Trading Engine": <BarChart3 className="h-5 w-5" />,
  "Notification System": <Bell className="h-5 w-5" />,
  "Signal Monitor": <Radio className="h-5 w-5" />,
};

function statusColor(status: string) {
  switch (status) {
    case "UP":
      return "text-emerald-500";
    case "DEGRADED":
      return "text-yellow-500";
    case "DOWN":
      return "text-red-500";
    default:
      return "text-muted-foreground";
  }
}

function statusBg(status: string) {
  switch (status) {
    case "UP":
      return "bg-emerald-500/10 border-emerald-500/20";
    case "DEGRADED":
      return "bg-yellow-500/10 border-yellow-500/20";
    case "DOWN":
      return "bg-red-500/10 border-red-500/20";
    default:
      return "bg-muted border-border";
  }
}

function StatusIcon({ status, className }: { status: string; className?: string }) {
  switch (status) {
    case "UP":
      return <CheckCircle2 className={`text-emerald-500 ${className || ""}`} />;
    case "DEGRADED":
      return <AlertTriangle className={`text-yellow-500 ${className || ""}`} />;
    case "DOWN":
      return <XCircle className={`text-red-500 ${className || ""}`} />;
    default:
      return null;
  }
}

function ServiceCard({ service, t }: { service: PublicServiceStatus; t: (key: string) => string }) {
  const nameKey = `status.service.${service.name.replace(/\s+/g, "")}`;
  const descKey = `status.serviceDesc.${service.name.replace(/\s+/g, "")}`;
  const displayName = t(nameKey) !== nameKey ? t(nameKey) : service.name;
  const displayDesc = t(descKey) !== descKey ? t(descKey) : service.description;

  return (
    <div className={`rounded-lg border p-4 ${statusBg(service.status)} transition-colors`}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span className={statusColor(service.status)}>
            {SERVICE_ICONS[service.name] || <CheckCircle2 className="h-5 w-5" />}
          </span>
          <div>
            <p className="font-medium text-foreground">{displayName}</p>
            <p className="text-sm text-muted-foreground">{displayDesc}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <StatusIcon status={service.status} className="h-5 w-5" />
          <span className={`text-sm font-medium ${statusColor(service.status)}`}>
            {service.status === "UP"
              ? t("status.operational")
              : service.status === "DEGRADED"
                ? t("status.degraded")
                : t("status.down")}
          </span>
        </div>
      </div>
    </div>
  );
}

export default function StatusPage() {
  const { t } = useT();
  const [data, setData] = useState<PublicStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchStatus = useCallback(async () => {
    try {
      const result = await getPublicSystemStatus();
      setData(result);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchStatus();
    const interval = setInterval(fetchStatus, 60_000);
    return () => clearInterval(interval);
  }, [fetchStatus]);

  return (
    <div className="min-h-screen bg-background">
      <div className="max-w-2xl mx-auto px-4 py-12">
        {/* Header */}
        <div className="text-center mb-10">
          <h1 className="text-3xl font-bold text-emerald-500 mb-2">HookFi</h1>
          <h2 className="text-xl font-semibold text-foreground">{t("status.title")}</h2>
        </div>

        {loading && (
          <div className="flex items-center justify-center py-20">
            <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-emerald-500" />
          </div>
        )}

        {error && (
          <div className="text-center py-12">
            <p className="text-red-500 mb-4">{error}</p>
            <button
              onClick={fetchStatus}
              className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground"
            >
              <RefreshCw className="h-4 w-4" />
              {t("common.retry")}
            </button>
          </div>
        )}

        {!loading && !error && data && (
          <>
            {/* Overall Status Banner */}
            <div
              className={`rounded-xl border p-6 mb-8 text-center ${statusBg(data.overallStatus)}`}
            >
              <StatusIcon status={data.overallStatus} className="h-8 w-8 mx-auto mb-3" />
              <p className="text-lg font-semibold text-foreground">
                {data.overallStatus === "UP"
                  ? t("status.allOperational")
                  : t("status.someIssues")}
              </p>
            </div>

            {/* Service Cards */}
            <div className="space-y-3 mb-8">
              {data.services.map((service) => (
                <ServiceCard key={service.name} service={service} t={t} />
              ))}
            </div>

            {/* Footer */}
            <div className="text-center text-sm text-muted-foreground space-y-1">
              <p>
                {t("status.lastChecked")}:{" "}
                {new Date(data.checkedAt).toLocaleString()}
              </p>
              <p className="flex items-center justify-center gap-1">
                <RefreshCw className="h-3 w-3" />
                {t("status.autoRefresh")}
              </p>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
