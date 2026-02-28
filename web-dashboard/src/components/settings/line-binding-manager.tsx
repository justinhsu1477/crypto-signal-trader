"use client";

import { useEffect, useState, useCallback } from "react";
import type { LineBindingStatus } from "@/types";
import {
  getLineBinding,
  generateLineCode,
  unbindLine,
  updateLineNotificationStatus,
} from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Switch } from "@/components/ui/switch";
import { Label } from "@/components/ui/label";
import { toast } from "sonner";
import { useT } from "@/lib/i18n/i18n-context";
import {
  Link2,
  Unlink,
  Copy,
  RefreshCw,
  ExternalLink,
  Bell,
  BellOff,
} from "lucide-react";

const LINE_BOT_URL = "https://lin.ee/9ga4egy";

export function LineBindingManager() {
  const { t } = useT();

  // State
  const [binding, setBinding] = useState<LineBindingStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Linking code state
  const [linkingCode, setLinkingCode] = useState<string | null>(null);
  const [codeExpiry, setCodeExpiry] = useState<number>(0); // seconds remaining
  const [generating, setGenerating] = useState(false);

  // Action state
  const [actionLoading, setActionLoading] = useState(false);

  // Fetch binding status
  const fetchBinding = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getLineBinding();
      setBinding(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : t("common.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    fetchBinding();
  }, [fetchBinding]);

  // Countdown timer for linking code
  useEffect(() => {
    if (codeExpiry <= 0) {
      if (linkingCode) setLinkingCode(null);
      return;
    }
    const timer = setInterval(() => {
      setCodeExpiry((prev) => prev - 1);
    }, 1000);
    return () => clearInterval(timer);
  }, [codeExpiry, linkingCode]);

  // Generate linking code
  async function handleGenerateCode() {
    setGenerating(true);
    try {
      const data = await generateLineCode();
      setLinkingCode(data.code);
      setCodeExpiry(data.expiresInMinutes * 60);
      toast.success(t("settings.lineCodeGenerated"));
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("common.loadFailed"));
    } finally {
      setGenerating(false);
    }
  }

  // Copy code to clipboard
  function handleCopyCode() {
    if (linkingCode) {
      navigator.clipboard.writeText(linkingCode);
      toast.success(t("common.copied"));
    }
  }

  // Unbind LINE
  async function handleUnbind() {
    if (!confirm(t("settings.lineConfirmUnbind"))) return;

    setActionLoading(true);
    try {
      await unbindLine();
      toast.success(t("settings.lineUnbound"));
      setBinding((prev) =>
        prev ? { ...prev, bound: false, displayName: undefined, linkedAt: undefined } : prev
      );
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("common.loadFailed"));
    } finally {
      setActionLoading(false);
    }
  }

  // Toggle LINE notification
  async function handleToggleNotification(enabled: boolean) {
    setActionLoading(true);
    try {
      await updateLineNotificationStatus(enabled);
      setBinding((prev) =>
        prev ? { ...prev, lineNotificationEnabled: enabled } : prev
      );
      toast.success(
        enabled
          ? t("settings.lineNotificationEnabled")
          : t("settings.lineNotificationDisabled")
      );
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("common.loadFailed"));
    } finally {
      setActionLoading(false);
    }
  }

  // Format countdown
  function formatCountdown(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${s.toString().padStart(2, "0")}`;
  }

  // Loading state
  if (loading) {
    return (
      <div className="flex items-center justify-center py-8 text-muted-foreground text-sm">
        {t("common.loading")}
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="text-center py-8">
        <p className="text-destructive text-sm">{error}</p>
        <Button variant="outline" size="sm" className="mt-2" onClick={fetchBinding}>
          <RefreshCw className="h-4 w-4 mr-1" />
          {t("common.retry")}
        </Button>
      </div>
    );
  }

  const isBound = binding?.bound ?? false;

  return (
    <div className="space-y-6">
      {/* Binding Status */}
      <div className="rounded-lg border p-4 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <svg className="h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
              <path className="text-green-400" d="M19.365 9.863c.349 0 .63.285.63.631 0 .345-.281.63-.63.63H17.61v1.125h1.755c.349 0 .63.283.63.63 0 .344-.281.629-.63.629h-2.386c-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63h2.386c.346 0 .627.285.627.63 0 .349-.281.63-.63.63H17.61v1.125h1.755zm-3.855 3.016c0 .27-.174.51-.432.596-.064.021-.133.031-.199.031-.211 0-.391-.09-.51-.25l-2.443-3.317v2.94c0 .344-.279.629-.631.629-.346 0-.626-.285-.626-.629V8.108c0-.27.173-.51.43-.595.06-.023.136-.033.194-.033.195 0 .375.104.495.254l2.462 3.33V8.108c0-.345.282-.63.63-.63.345 0 .63.285.63.63v4.771zm-5.741 0c0 .344-.282.629-.631.629-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63.346 0 .628.285.628.63v4.771zm-2.466.629H4.917c-.345 0-.63-.285-.63-.629V8.108c0-.345.285-.63.63-.63.348 0 .63.285.63.63v4.141h1.756c.348 0 .629.283.629.63 0 .344-.282.629-.629.629M24 10.314C24 4.943 18.615.572 12 .572S0 4.943 0 10.314c0 4.811 4.27 8.842 10.035 9.608.391.082.923.258 1.058.59.12.301.079.766.038 1.08l-.164 1.02c-.045.301-.24 1.186 1.049.645 1.291-.539 6.916-4.078 9.436-6.975C23.176 14.393 24 12.458 24 10.314" />
            </svg>
            <span className="font-medium">{t("settings.lineBindingStatus")}</span>
          </div>
          <Badge variant={isBound ? "default" : "secondary"}>
            {isBound ? t("settings.lineBound") : t("settings.lineUnbound")}
          </Badge>
        </div>

        {/* Bound: show details */}
        {isBound && binding && (
          <div className="space-y-3">
            {binding.displayName && (
              <div className="text-sm text-muted-foreground">
                {t("settings.lineDisplayName")}: <span className="text-foreground">{binding.displayName}</span>
              </div>
            )}
            {binding.linkedAt && (
              <div className="text-sm text-muted-foreground">
                {t("settings.lineLinkedAt")}: <span className="text-foreground">
                  {new Date(binding.linkedAt).toLocaleDateString()}
                </span>
              </div>
            )}

            {/* Notification toggle */}
            <div className="flex items-center justify-between pt-2">
              <div className="flex items-center gap-2">
                {binding.lineNotificationEnabled ? (
                  <Bell className="h-4 w-4 text-green-400" />
                ) : (
                  <BellOff className="h-4 w-4 text-muted-foreground" />
                )}
                <Label htmlFor="line-notification-toggle">
                  {t("settings.lineNotificationToggle")}
                </Label>
              </div>
              <Switch
                id="line-notification-toggle"
                checked={binding.lineNotificationEnabled}
                onCheckedChange={handleToggleNotification}
                disabled={actionLoading}
              />
            </div>

            {/* Unbind button */}
            <Button
              variant="outline"
              size="sm"
              className="text-destructive hover:text-destructive"
              onClick={handleUnbind}
              disabled={actionLoading}
            >
              <Unlink className="h-4 w-4 mr-1" />
              {t("settings.lineUnbindBtn")}
            </Button>
          </div>
        )}

        {/* Not bound: show linking flow */}
        {!isBound && (
          <div className="space-y-4">
            {/* Generate code button or show code */}
            {linkingCode && codeExpiry > 0 ? (
              <div className="space-y-3">
                <div className="flex items-center gap-3">
                  <code className="text-2xl font-mono font-bold tracking-widest bg-muted px-4 py-2 rounded-lg">
                    {linkingCode}
                  </code>
                  <Button variant="ghost" size="icon" onClick={handleCopyCode}>
                    <Copy className="h-4 w-4" />
                  </Button>
                </div>
                <p className="text-sm text-muted-foreground">
                  {t("settings.lineCodeExpiry").replace(
                    "{time}",
                    formatCountdown(codeExpiry)
                  )}
                </p>
                <p className="text-sm text-muted-foreground">
                  {t("settings.lineCodeInstruction")}
                </p>
              </div>
            ) : (
              <Button onClick={handleGenerateCode} disabled={generating}>
                <Link2 className="h-4 w-4 mr-1" />
                {generating ? t("common.loading") : t("settings.lineGenerateCode")}
              </Button>
            )}

            {/* LINE bot link */}
            <a
              href={LINE_BOT_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-sm text-green-400 hover:underline"
            >
              <ExternalLink className="h-3.5 w-3.5" />
              {t("settings.lineAddFriend")}
            </a>
          </div>
        )}
      </div>

      {/* How to bind instructions */}
      {!isBound && (
        <div className="rounded-lg border border-dashed p-4">
          <h4 className="text-sm font-medium mb-3">{t("settings.lineHowToBind")}</h4>
          <ol className="text-sm text-muted-foreground space-y-2 list-decimal list-inside">
            <li>{t("settings.lineStep1")}</li>
            <li>{t("settings.lineStep2")}</li>
            <li>{t("settings.lineStep3")}</li>
            <li>{t("settings.lineStep4")}</li>
          </ol>
        </div>
      )}
    </div>
  );
}
