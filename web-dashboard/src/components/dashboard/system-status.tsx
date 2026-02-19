"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getMonitorStatus, getStreamStatus } from "@/lib/api";
import { useT } from "@/lib/i18n/i18n-context";
import type { MonitorStatus, StreamStatus } from "@/types";

interface SystemStatusProps {
  circuitBreakerActive: boolean;
}

function StatusDot({ connected }: { connected: boolean }) {
  return (
    <span
      className={`inline-block h-2.5 w-2.5 rounded-full ${
        connected ? "bg-emerald-500" : "bg-red-500"
      }`}
    />
  );
}

export function SystemStatus({ circuitBreakerActive }: SystemStatusProps) {
  const { t } = useT();
  const [monitor, setMonitor] = useState<MonitorStatus | null>(null);
  const [stream, setStream] = useState<StreamStatus | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function fetchStatus() {
      try {
        const [m, s] = await Promise.all([
          getMonitorStatus(),
          getStreamStatus(),
        ]);
        setMonitor(m);
        setStream(s);
      } catch {
        // silently fail — status will show as disconnected
      } finally {
        setLoading(false);
      }
    }
    fetchStatus();
  }, []);

  const indicators = [
    {
      label: "Discord Monitor",
      connected: monitor?.monitorConnected ?? false,
    },
    {
      label: "WebSocket Stream",
      connected: stream?.connected ?? false,
    },
    {
      label: "Circuit Breaker",
      connected: !circuitBreakerActive,
    },
  ];

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t("dashboard.systemStatus")}</CardTitle>
      </CardHeader>
      <CardContent>
        {loading ? (
          <p className="py-4 text-center text-sm text-muted-foreground">
            {t("common.loading")}
          </p>
        ) : (
          <div className="space-y-3">
            {indicators.map((item) => (
              <div
                key={item.label}
                className="flex items-center justify-between"
              >
                <span className="text-sm text-muted-foreground">
                  {item.label}
                </span>
                <div className="flex items-center gap-2">
                  <StatusDot connected={item.connected} />
                  <span className="text-sm font-medium">
                    {item.connected ? t("common.connected") : t("common.disconnected")}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
