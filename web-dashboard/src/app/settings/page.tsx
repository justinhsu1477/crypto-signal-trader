"use client";

import { useEffect, useState } from "react";
import type { UserProfile, ApiKeyMetadata, AutoTradeStatus } from "@/types";
import {
  getUserProfile,
  getApiKeys,
  saveApiKey,
  getAutoTradeStatus,
  updateAutoTradeStatus,
} from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
import { cn } from "@/lib/utils";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { Switch } from "@/components/ui/switch";
import { useT } from "@/lib/i18n/i18n-context";
import { DiscordWebhookManager } from "@/components/settings/discord-webhook-manager";
import {
  User,
  KeyRound,
  Bot,
  Bell,
} from "lucide-react";

// ─── Settings sections ───
type SettingsSection = "profile" | "api-keys" | "trading" | "notifications";

export default function SettingsPage() {
  const { t } = useT();
  const [activeSection, setActiveSection] =
    useState<SettingsSection>("profile");

  // ─── Profile state ───
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [profileLoading, setProfileLoading] = useState(true);
  const [profileError, setProfileError] = useState<string | null>(null);

  // ─── API Keys state ───
  const [apiKeys, setApiKeys] = useState<ApiKeyMetadata[]>([]);
  const [keysLoading, setKeysLoading] = useState(true);
  const [keysError, setKeysError] = useState<string | null>(null);

  // ─── Form state ───
  const [exchange, setExchange] = useState("BINANCE");
  const [apiKeyInput, setApiKeyInput] = useState("");
  const [secretKeyInput, setSecretKeyInput] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveMessage, setSaveMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);

  // ─── Auto Trade state ───
  const [autoTradeStatus, setAutoTradeStatus] =
    useState<AutoTradeStatus | null>(null);
  const [autoTradeLoading, setAutoTradeLoading] = useState(true);
  const [autoTradeError, setAutoTradeError] = useState<string | null>(null);
  const [autoTradeUpdating, setAutoTradeUpdating] = useState(false);

  // ─── Webhook readiness ───
  const [hasActiveWebhook, setHasActiveWebhook] = useState(false);

  // ─── Prerequisite check ───
  const hasBinanceKey = apiKeys.some(
    (k) => k.exchange === "BINANCE" && k.hasApiKey
  );
  const canEnableAutoTrade = hasBinanceKey && hasActiveWebhook;

  // ─── Sidebar nav items ───
  const navItems: {
    id: SettingsSection;
    icon: React.ElementType;
    labelKey: string;
    descKey: string;
  }[] = [
    {
      id: "profile",
      icon: User,
      labelKey: "settings.navProfile",
      descKey: "settings.navProfileDesc",
    },
    {
      id: "api-keys",
      icon: KeyRound,
      labelKey: "settings.navApiKeys",
      descKey: "settings.navApiKeysDesc",
    },
    {
      id: "trading",
      icon: Bot,
      labelKey: "settings.navTrading",
      descKey: "settings.navTradingDesc",
    },
    {
      id: "notifications",
      icon: Bell,
      labelKey: "settings.navNotifications",
      descKey: "settings.navNotificationsDesc",
    },
  ];

  // ─── Data fetching ───
  useEffect(() => {
    let cancelled = false;
    async function fetchProfile() {
      setProfileLoading(true);
      setProfileError(null);
      try {
        const data = await getUserProfile();
        if (!cancelled) setProfile(data);
      } catch (err) {
        if (!cancelled)
          setProfileError(
            err instanceof Error ? err.message : t("common.loadFailed")
          );
      } finally {
        if (!cancelled) setProfileLoading(false);
      }
    }
    fetchProfile();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function fetchKeys() {
      setKeysLoading(true);
      setKeysError(null);
      try {
        const data = await getApiKeys();
        if (!cancelled) setApiKeys(data);
      } catch (err) {
        if (!cancelled)
          setKeysError(
            err instanceof Error ? err.message : t("common.loadFailed")
          );
      } finally {
        if (!cancelled) setKeysLoading(false);
      }
    }
    fetchKeys();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function fetchAutoTradeStatus() {
      setAutoTradeLoading(true);
      setAutoTradeError(null);
      try {
        const data = await getAutoTradeStatus();
        if (!cancelled) setAutoTradeStatus(data);
      } catch (err) {
        if (!cancelled)
          setAutoTradeError(
            err instanceof Error ? err.message : t("common.loadFailed")
          );
      } finally {
        if (!cancelled) setAutoTradeLoading(false);
      }
    }
    fetchAutoTradeStatus();
    return () => {
      cancelled = true;
    };
  }, []);

  // ─── Handlers ───
  async function handleSaveApiKey() {
    if (!apiKeyInput.trim() || !secretKeyInput.trim()) {
      setSaveMessage({ type: "error", text: t("settings.fillRequired") });
      return;
    }
    setSaving(true);
    setSaveMessage(null);
    try {
      const result = await saveApiKey({
        exchange,
        apiKey: apiKeyInput.trim(),
        secretKey: secretKeyInput.trim(),
      });
      setSaveMessage({
        type: "success",
        text: result.message || t("common.saveSuccess"),
      });
      setApiKeyInput("");
      setSecretKeyInput("");
      const updatedKeys = await getApiKeys();
      setApiKeys(updatedKeys);
    } catch (err) {
      setSaveMessage({
        type: "error",
        text: err instanceof Error ? err.message : t("common.saveFailed"),
      });
    } finally {
      setSaving(false);
    }
  }

  async function handleToggleAutoTrade(enabled: boolean) {
    setAutoTradeUpdating(true);
    try {
      const result = await updateAutoTradeStatus(enabled);
      setAutoTradeStatus(result);
      setSaveMessage({ type: "success", text: result.message });
    } catch (err) {
      setSaveMessage({
        type: "error",
        text: err instanceof Error ? err.message : t("common.saveFailed"),
      });
      setAutoTradeStatus((prev) =>
        prev ? { ...prev, autoTradeEnabled: !enabled } : null
      );
    } finally {
      setAutoTradeUpdating(false);
    }
  }

  // ─── Section renderers ───
  function renderProfile() {
    return (
      <div className="space-y-6">
        <div>
          <h2 className="text-lg font-semibold">{t("settings.profile")}</h2>
          <p className="text-sm text-muted-foreground">
            {t("settings.navProfileDesc")}
          </p>
        </div>
        <Separator />

        {profileLoading && (
          <div className="flex items-center justify-center py-12">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
          </div>
        )}

        {profileError && (
          <div className="text-center py-6 text-red-500">{profileError}</div>
        )}

        {!profileLoading && !profileError && profile && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
            <div className="space-y-1">
              <Label className="text-muted-foreground text-xs">User ID</Label>
              <p className="font-mono text-sm">{profile.userId}</p>
            </div>
            <div className="space-y-1">
              <Label className="text-muted-foreground text-xs">Email</Label>
              <p className="text-sm">{profile.email}</p>
            </div>
            <div className="space-y-1">
              <Label className="text-muted-foreground text-xs">
                {t("settings.name")}
              </Label>
              <p className="text-sm">{profile.name}</p>
            </div>
            <div className="space-y-1">
              <Label className="text-muted-foreground text-xs">
                {t("settings.role")}
              </Label>
              <p className="text-sm">
                <Badge variant="outline">{profile.role}</Badge>
              </p>
            </div>
            <div className="space-y-1">
              <Label className="text-muted-foreground text-xs">
                {t("settings.createdAt")}
              </Label>
              <p className="text-sm">{formatDateTime(profile.createdAt)}</p>
            </div>
          </div>
        )}
      </div>
    );
  }

  function renderApiKeys() {
    return (
      <div className="space-y-6">
        <div>
          <h2 className="text-lg font-semibold">
            {t("settings.apiKeyManagement")}
          </h2>
          <p className="text-sm text-muted-foreground">
            {t("settings.navApiKeysDesc")}
          </p>
        </div>
        <Separator />

        {keysLoading && (
          <div className="flex items-center justify-center py-12">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
          </div>
        )}

        {keysError && (
          <div className="text-center py-6 text-red-500">{keysError}</div>
        )}

        {!keysLoading && !keysError && (
          <>
            {/* Current keys */}
            {apiKeys.length > 0 ? (
              <div className="space-y-3">
                <Label className="text-sm font-medium">
                  {t("settings.configuredExchanges")}
                </Label>
                <div className="space-y-2">
                  {apiKeys.map((key) => (
                    <div
                      key={key.exchange}
                      className="flex items-center justify-between p-3 border rounded-lg"
                    >
                      <div className="flex items-center gap-3">
                        <span className="font-medium text-sm">
                          {key.exchange}
                        </span>
                        {key.hasApiKey ? (
                          <Badge className="bg-emerald-500/15 text-emerald-500 border-emerald-500/25">
                            {t("settings.configured")}
                          </Badge>
                        ) : (
                          <Badge variant="secondary">
                            {t("settings.notConfigured")}
                          </Badge>
                        )}
                      </div>
                      {key.updatedAt && (
                        <span className="text-xs text-muted-foreground">
                          {t("settings.updatedAt", {
                            time: formatDateTime(key.updatedAt),
                          })}
                        </span>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">
                {t("settings.noApiKeys")}
              </p>
            )}

            <Separator />

            {/* Add / Update form */}
            <div className="space-y-4">
              <Label className="text-sm font-medium">
                {t("settings.addUpdateApiKey")}
              </Label>

              <div className="grid grid-cols-1 gap-4">
                <div className="space-y-2">
                  <Label
                    htmlFor="exchange"
                    className="text-xs text-muted-foreground"
                  >
                    Exchange
                  </Label>
                  <Input
                    id="exchange"
                    value={exchange}
                    onChange={(e) => setExchange(e.target.value)}
                    placeholder="BINANCE"
                  />
                </div>

                <div className="space-y-2">
                  <Label
                    htmlFor="apiKey"
                    className="text-xs text-muted-foreground"
                  >
                    API Key
                  </Label>
                  <Input
                    id="apiKey"
                    type="password"
                    value={apiKeyInput}
                    onChange={(e) => setApiKeyInput(e.target.value)}
                    placeholder={t("settings.apiKeyPlaceholder")}
                  />
                </div>

                <div className="space-y-2">
                  <Label
                    htmlFor="secretKey"
                    className="text-xs text-muted-foreground"
                  >
                    Secret Key
                  </Label>
                  <Input
                    id="secretKey"
                    type="password"
                    value={secretKeyInput}
                    onChange={(e) => setSecretKeyInput(e.target.value)}
                    placeholder={t("settings.secretKeyPlaceholder")}
                  />
                </div>
              </div>

              {saveMessage && (
                <p
                  className={`text-sm ${
                    saveMessage.type === "success"
                      ? "text-emerald-500"
                      : "text-red-500"
                  }`}
                >
                  {saveMessage.text}
                </p>
              )}

              <Button onClick={handleSaveApiKey} disabled={saving}>
                {saving ? t("common.saving") : t("settings.saveApiKey")}
              </Button>
            </div>
          </>
        )}
      </div>
    );
  }

  function renderTrading() {
    return (
      <div className="space-y-6">
        <div>
          <h2 className="text-lg font-semibold">{t("settings.autoTrade")}</h2>
          <p className="text-sm text-muted-foreground">
            {t("settings.navTradingDesc")}
          </p>
        </div>
        <Separator />

        {autoTradeLoading && (
          <div className="flex items-center justify-center py-12">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
          </div>
        )}

        {autoTradeError && (
          <div className="text-center py-6 text-red-500">
            {autoTradeError}
          </div>
        )}

        {!autoTradeLoading && !autoTradeError && autoTradeStatus && (
          <div className="space-y-4">
            <div className="flex items-center justify-between p-4 border rounded-lg bg-muted/30">
              <div className="space-y-1">
                <Label className="text-base font-medium">
                  {t("settings.autoTradeLabel")}
                </Label>
                <p className="text-sm text-muted-foreground">
                  {t("settings.autoTradeDescription")}
                </p>
              </div>
              <Switch
                checked={autoTradeStatus.autoTradeEnabled}
                onCheckedChange={handleToggleAutoTrade}
                disabled={autoTradeUpdating || !canEnableAutoTrade}
              />
            </div>

            {/* Prerequisite warning */}
            {!canEnableAutoTrade && (
              <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700 dark:bg-red-950/30 dark:border-red-900 dark:text-red-300 space-y-1">
                <p className="font-medium">
                  🔒 {t("settings.autoTradePrerequisite")}
                </p>
                <ul className="list-disc list-inside text-xs space-y-0.5">
                  {!hasBinanceKey && (
                    <li>❌ {t("settings.autoTradeMissingApiKey")}</li>
                  )}
                  {!hasActiveWebhook && (
                    <li>❌ {t("settings.autoTradeMissingWebhook")}</li>
                  )}
                </ul>
              </div>
            )}

            {/* Status indicator */}
            {canEnableAutoTrade && autoTradeStatus.autoTradeEnabled ? (
              <div className="p-3 bg-emerald-50 border border-emerald-200 rounded-lg text-sm text-emerald-700 dark:bg-emerald-950/30 dark:border-emerald-900 dark:text-emerald-300">
                ✓ {t("settings.autoTradeEnabled")}
              </div>
            ) : canEnableAutoTrade && !autoTradeStatus.autoTradeEnabled ? (
              <div className="p-3 bg-yellow-50 border border-yellow-200 rounded-lg text-sm text-yellow-700 dark:bg-yellow-950/30 dark:border-yellow-900 dark:text-yellow-300">
                ⚠️ {t("settings.autoTradeDisabled")}
              </div>
            ) : null}
          </div>
        )}
      </div>
    );
  }

  function renderNotifications() {
    return (
      <div className="space-y-6">
        <div>
          <h2 className="text-lg font-semibold">
            {t("settings.discordNotification")}
          </h2>
          <p className="text-sm text-muted-foreground">
            {t("settings.navNotificationsDesc")}
          </p>
        </div>
        <Separator />
        <DiscordWebhookManager onWebhooksChange={setHasActiveWebhook} />
      </div>
    );
  }

  const sectionRenderers: Record<SettingsSection, () => React.ReactNode> = {
    profile: renderProfile,
    "api-keys": renderApiKeys,
    trading: renderTrading,
    notifications: renderNotifications,
  };

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("settings.title")}</h1>

      <div className="flex flex-col lg:flex-row gap-6">
        {/* ─── Left sidebar nav ─── */}
        <nav className="lg:w-64 shrink-0">
          {/* Desktop: vertical sidebar card */}
          <Card className="hidden lg:block">
            <CardContent className="p-2">
              <div className="space-y-1">
                {navItems.map((item) => {
                  const Icon = item.icon;
                  const isActive = activeSection === item.id;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => setActiveSection(item.id)}
                      className={cn(
                        "w-full flex items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-colors",
                        isActive
                          ? "bg-primary/10 text-primary"
                          : "text-muted-foreground hover:bg-muted hover:text-foreground"
                      )}
                    >
                      <Icon className="h-4 w-4 shrink-0" />
                      <div className="min-w-0">
                        <p
                          className={cn(
                            "text-sm font-medium truncate",
                            isActive && "text-primary"
                          )}
                        >
                          {t(item.labelKey)}
                        </p>
                        <p className="text-xs text-muted-foreground truncate hidden xl:block">
                          {t(item.descKey)}
                        </p>
                      </div>
                    </button>
                  );
                })}
              </div>
            </CardContent>
          </Card>

          {/* Mobile: horizontal scroll nav */}
          <div className="flex lg:hidden gap-2 overflow-x-auto pb-2">
            {navItems.map((item) => {
              const Icon = item.icon;
              const isActive = activeSection === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setActiveSection(item.id)}
                  className={cn(
                    "flex items-center gap-2 rounded-lg px-3 py-2 text-sm whitespace-nowrap border transition-colors",
                    isActive
                      ? "bg-primary/10 text-primary border-primary/30"
                      : "text-muted-foreground border-transparent hover:bg-muted"
                  )}
                >
                  <Icon className="h-4 w-4" />
                  {t(item.labelKey)}
                </button>
              );
            })}
          </div>
        </nav>

        {/* ─── Right content area ─── */}
        <Card className="flex-1 min-w-0">
          <CardContent className="p-6">
            {sectionRenderers[activeSection]()}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
