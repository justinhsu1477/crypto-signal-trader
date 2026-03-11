"use client";

import { useState, useEffect, useCallback } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getMonitorChannels, updateMonitorChannels } from "@/lib/api";
import type { MonitorChannelsResponse } from "@/lib/api";
import { toast } from "sonner";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import {
  Loader2,
  Wifi,
  WifiOff,
  Plus,
  Trash2,
  Save,
  RefreshCw,
} from "lucide-react";

export default function MonitorSettingsPage() {
  const { t } = useT();

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [data, setData] = useState<MonitorChannelsResponse | null>(null);
  const [channelIds, setChannelIds] = useState<string[]>([]);
  const [newChannelId, setNewChannelId] = useState("");
  const [error, setError] = useState("");

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const res = await getMonitorChannels();
      setData(res);
      setChannelIds(res.channelIds || []);
    } catch {
      toast.error(t("common.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  function handleAddChannel() {
    const trimmed = newChannelId.trim();
    if (!trimmed) {
      setError(t("admin.channelIdEmpty"));
      return;
    }
    if (channelIds.includes(trimmed)) {
      setError(t("admin.channelIdDuplicate"));
      return;
    }
    setChannelIds([...channelIds, trimmed]);
    setNewChannelId("");
    setError("");
  }

  function handleRemoveChannel(id: string) {
    setChannelIds(channelIds.filter((c) => c !== id));
  }

  async function handleSave() {
    if (channelIds.length === 0) {
      setError(t("admin.channelIdEmpty"));
      return;
    }
    setSaving(true);
    try {
      await updateMonitorChannels({ channelIds });
      toast.success(t("admin.saveChannelsSuccess"));
      await fetchData();
    } catch {
      toast.error(t("admin.saveChannelsFailed"));
    } finally {
      setSaving(false);
    }
  }

  const hasChanges =
    data && JSON.stringify(channelIds.sort()) !== JSON.stringify([...(data.channelIds || [])].sort());

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t("admin.monitorSettings")}</h1>
        <Button variant="outline" size="sm" onClick={fetchData} disabled={loading}>
          <RefreshCw className="h-4 w-4 mr-1" />
          Refresh
        </Button>
      </div>

      {/* Status Card */}
      <div className="rounded-xl border border-border bg-card p-6">
        <div className="flex items-center gap-2 mb-4">
          {data?.monitorOnline ? (
            <Wifi className="h-5 w-5 text-green-400" />
          ) : (
            <WifiOff className="h-5 w-5 text-red-400" />
          )}
          <h2 className="text-lg font-semibold">{t("admin.monitorStatus")}</h2>
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <p className="text-sm text-muted-foreground">{t("admin.status")}</p>
            <p className={`font-medium ${data?.monitorOnline ? "text-green-400" : "text-red-400"}`}>
              {data?.monitorOnline ? t("admin.monitorOnline") : t("admin.monitorOffline")}
            </p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">{t("admin.lastHeartbeat")}</p>
            <p className="font-medium text-sm">
              {data?.lastHeartbeat
                ? new Date(data.lastHeartbeat).toLocaleString()
                : "—"}
            </p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">{t("admin.grpcConnections")}</p>
            <p className="font-medium">{data?.connectedMonitors ?? 0}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">{t("admin.configVersion")}</p>
            <p className="font-medium">v{data?.configVersion ?? 0}</p>
          </div>
        </div>
      </div>

      <Separator />

      {/* Channel IDs Management */}
      <div className="rounded-xl border border-border bg-card p-6">
        <h2 className="text-lg font-semibold mb-1">{t("admin.channelIds")}</h2>
        <p className="text-sm text-muted-foreground mb-4">
          {t("admin.channelIdsHint")}
        </p>

        {/* Current Channel List */}
        <div className="space-y-2 mb-4">
          {channelIds.map((id) => (
            <div
              key={id}
              className="flex items-center justify-between rounded-lg border border-border bg-background px-4 py-2"
            >
              <code className="text-sm font-mono">{id}</code>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleRemoveChannel(id)}
                className="text-red-400 hover:text-red-300 hover:bg-red-400/10"
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          ))}
          {channelIds.length === 0 && (
            <p className="text-sm text-muted-foreground italic py-2">
              {t("admin.channelIdEmpty")}
            </p>
          )}
        </div>

        {/* Add new channel */}
        <div className="flex gap-2">
          <Input
            placeholder={t("admin.channelIdPlaceholder")}
            value={newChannelId}
            onChange={(e) => {
              setNewChannelId(e.target.value);
              setError("");
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") handleAddChannel();
            }}
            className="font-mono"
          />
          <Button variant="outline" onClick={handleAddChannel}>
            <Plus className="h-4 w-4 mr-1" />
            {t("admin.addChannel")}
          </Button>
        </div>
        {error && <p className="text-sm text-red-400 mt-2">{error}</p>}

        {/* Save button */}
        <div className="mt-6 flex items-center gap-3">
          <Button
            onClick={handleSave}
            disabled={saving || !hasChanges}
          >
            {saving ? (
              <Loader2 className="h-4 w-4 mr-1 animate-spin" />
            ) : (
              <Save className="h-4 w-4 mr-1" />
            )}
            {t("admin.saveChannels")}
          </Button>
          {hasChanges && (
            <span className="text-sm text-yellow-400">
              *
            </span>
          )}
        </div>
      </div>
    </div>
  );
}
