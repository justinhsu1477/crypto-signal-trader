"use client";

import { useEffect, useState } from "react";
import type { UserDiscordWebhook } from "@/types";
import {
  getDiscordWebhooks,
  createDiscordWebhook,
  disableDiscordWebhook,
  deleteDiscordWebhook,
} from "@/lib/api";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { toast } from "sonner";
import { useT } from "@/lib/i18n/i18n-context";

interface DiscordWebhookManagerProps {
  onWebhooksChange?: (hasActiveWebhook: boolean) => void;
}

export function DiscordWebhookManager({
  onWebhooksChange,
}: DiscordWebhookManagerProps) {
  const { t } = useT();

  // State
  const [webhooks, setWebhooks] = useState<UserDiscordWebhook[]>([]);
  const [primaryId, setPrimaryId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [webhookName, setWebhookName] = useState("");
  const [webhookUrl, setWebhookUrl] = useState("");
  const [saving, setSaving] = useState(false);

  // Fetch webhooks
  useEffect(() => {
    let cancelled = false;

    async function fetchWebhooks() {
      setLoading(true);
      setError(null);
      try {
        const data = await getDiscordWebhooks();
        if (!cancelled) {
          setWebhooks(data.webhooks);
          setPrimaryId(data.primaryWebhookId);
          onWebhooksChange?.(data.webhooks.some((w) => w.enabled));
        }
      } catch (err) {
        if (!cancelled)
          setError(
            err instanceof Error ? err.message : t("common.loadFailed")
          );
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    fetchWebhooks();
    return () => {
      cancelled = true;
    };
  }, []);

  async function refreshWebhooks() {
    const data = await getDiscordWebhooks();
    setWebhooks(data.webhooks);
    setPrimaryId(data.primaryWebhookId);
    onWebhooksChange?.(data.webhooks.some((w) => w.enabled));
  }

  // Handle create webhook
  async function handleCreateWebhook() {
    if (!webhookUrl.trim()) {
      toast.error(t("settings.webhookUrlRequired"));
      return;
    }

    if (!webhookUrl.startsWith("https://discord.com/api/webhooks/")) {
      toast.error(t("settings.invalidWebhookUrl"));
      return;
    }

    setSaving(true);
    try {
      const result = await createDiscordWebhook({
        webhookUrl: webhookUrl.trim(),
        name: webhookName.trim() || "Discord Webhook",
      });

      toast.success(result.message);
      setWebhookName("");
      setWebhookUrl("");

      await refreshWebhooks();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("common.saveFailed"));
    } finally {
      setSaving(false);
    }
  }

  // Handle disable
  async function handleDisable(webhookId: string) {
    try {
      await disableDiscordWebhook(webhookId);
      await refreshWebhooks();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("common.saveFailed"));
    }
  }

  // Handle delete
  async function handleDelete(webhookId: string) {
    if (window.confirm(t("settings.confirmDelete"))) {
      try {
        await deleteDiscordWebhook(webhookId);
        await refreshWebhooks();
      } catch (err) {
        toast.error(err instanceof Error ? err.message : t("common.saveFailed"));
      }
    }
  }

  return (
    <div className="space-y-6">
      {/* Loading */}
      {loading && (
        <div className="flex items-center justify-center py-8">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
        </div>
      )}

      {/* Error */}
      {error && (
        <div className="text-center py-6 text-red-500">{error}</div>
      )}

      {/* Content */}
      {!loading && !error && (
        <>
          {/* Current webhooks */}
          {webhooks.length > 0 ? (
            <div className="space-y-3">
              <Label className="text-sm font-medium">
                {t("settings.currentWebhooks")}
              </Label>
              <div className="space-y-2">
                {webhooks.map((webhook) => (
                  <div
                    key={webhook.webhookId}
                    className="flex items-center justify-between p-3 border rounded-lg"
                  >
                    <div className="flex-1 space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="font-medium text-sm">
                          📌 {webhook.name}
                        </span>
                        {webhook.enabled && (
                          <Badge className="bg-emerald-500/15 text-emerald-500">
                            {t("settings.enabled")}
                          </Badge>
                        )}
                        {primaryId === webhook.webhookId && (
                          <Badge variant="outline">
                            {t("settings.primary")}
                          </Badge>
                        )}
                      </div>
                      <p className="text-xs font-mono text-muted-foreground break-all">
                        {webhook.webhookUrl.substring(0, 60)}...
                      </p>
                    </div>
                    <div className="flex gap-2">
                      {webhook.enabled && (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => handleDisable(webhook.webhookId)}
                        >
                          {t("settings.disable")}
                        </Button>
                      )}
                      <Button
                        size="sm"
                        variant="destructive"
                        onClick={() => handleDelete(webhook.webhookId)}
                      >
                        {t("settings.delete")}
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">
              {t("settings.noWebhooks")}
            </p>
          )}

          {/* Add new webhook form */}
          <div className="border-t pt-6 space-y-4">
            <Label className="text-sm font-medium">
              {t("settings.addNewWebhook")}
            </Label>

            <div className="space-y-3">
              <div className="space-y-2">
                <Label
                  htmlFor="webhookName"
                  className="text-xs text-muted-foreground"
                >
                  {t("settings.webhookName")}
                </Label>
                <Input
                  id="webhookName"
                  value={webhookName}
                  onChange={(e) => setWebhookName(e.target.value)}
                  placeholder={t("settings.webhookNamePlaceholder")}
                />
              </div>

              <div className="space-y-2">
                <Label
                  htmlFor="webhookUrl"
                  className="text-xs text-muted-foreground"
                >
                  Webhook URL
                </Label>
                <Input
                  id="webhookUrl"
                  value={webhookUrl}
                  onChange={(e) => setWebhookUrl(e.target.value)}
                  placeholder="https://discord.com/api/webhooks/..."
                  type="url"
                />
              </div>
            </div>

            <Button onClick={handleCreateWebhook} disabled={saving}>
              {saving ? t("common.saving") : t("settings.addWebhook")}
            </Button>

            {/* Help text */}
            <div className="p-3 bg-blue-50 border border-blue-200 rounded-lg text-sm text-blue-700 dark:bg-blue-950/30 dark:border-blue-900 dark:text-blue-300 space-y-2">
              <p className="font-medium">
                ℹ️ {t("settings.howToGetWebhook")}
              </p>
              <ol className="list-decimal list-inside space-y-1">
                <li>{t("settings.step1CreateChannel")}</li>
                <li>{t("settings.step2EditChannel")}</li>
                <li>{t("settings.step3CreateWebhook")}</li>
                <li>{t("settings.step4CopyUrl")}</li>
              </ol>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
