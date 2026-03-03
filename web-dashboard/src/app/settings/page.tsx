"use client";

import { useEffect, useState } from "react";
import type { UserProfile, ApiKeyMetadata, AutoTradeStatus } from "@/types";
import {
  getUserProfile,
  getApiKeys,
  saveApiKey,
  getAutoTradeStatus,
  updateAutoTradeStatus,
  changePassword,
  deleteAccount,
  apiLogout,
  getSubscriptionStatus,
} from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { Switch } from "@/components/ui/switch";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { useT } from "@/lib/i18n/i18n-context";
import { DiscordWebhookManager } from "@/components/settings/discord-webhook-manager";
import { LineBindingManager } from "@/components/settings/line-binding-manager";
import { TradeSettingsForm } from "@/components/settings/trade-settings-form";
import { SubscriptionManager } from "@/components/settings/subscription-manager";
import {
  User,
  KeyRound,
  Bot,
  Bell,
  CreditCard,
  Shield,
  Loader2,
  Eye,
  EyeOff,
  ChevronDown,
  ChevronUp,
  Copy,
  Check,
  AlertTriangle,
} from "lucide-react";

// ─── Settings sections ───
type SettingsSection = "profile" | "api-keys" | "trading" | "notifications" | "subscription" | "security";

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
  const [showApiKey, setShowApiKey] = useState(false);
  const [showSecretKey, setShowSecretKey] = useState(false);

  // ─── Auto Trade state ───
  const [autoTradeStatus, setAutoTradeStatus] =
    useState<AutoTradeStatus | null>(null);
  const [autoTradeLoading, setAutoTradeLoading] = useState(true);
  const [autoTradeError, setAutoTradeError] = useState<string | null>(null);
  const [autoTradeUpdating, setAutoTradeUpdating] = useState(false);

  // ─── Subscription state ───
  const [subscriptionActive, setSubscriptionActive] = useState(false);
  const [subscriptionLoading, setSubscriptionLoading] = useState(true);

  // ─── Webhook readiness ───
  const [hasActiveWebhook, setHasActiveWebhook] = useState(false);

  // ─── Security (Change Password) state ───
  const [currentPw, setCurrentPw] = useState("");
  const [newPw, setNewPw] = useState("");
  const [confirmPw, setConfirmPw] = useState("");
  const [pwError, setPwError] = useState("");
  const [pwSaving, setPwSaving] = useState(false);

  // ─── API Key Tutorial state ───
  const [tutorialOpen, setTutorialOpen] = useState<boolean | null>(null);
  const [ipCopied, setIpCopied] = useState(false);

  // ─── Auto Trade confirmation dialog state ───
  const [autoTradeConfirmOpen, setAutoTradeConfirmOpen] = useState(false);
  const [autoTradeConfirmValue, setAutoTradeConfirmValue] = useState(false);

  // ─── Delete Account state ───
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [deleteConfirmText, setDeleteConfirmText] = useState("");
  const [deleteLoading, setDeleteLoading] = useState(false);

  // ─── Prerequisite check ───
  const hasBinanceKey = apiKeys.some(
    (k) => k.exchange === "BINANCE" && k.hasApiKey
  );
  const canEnableAutoTrade = hasBinanceKey && subscriptionActive;
  const prerequisitesLoading = keysLoading || subscriptionLoading;

  // ─── Auto-expand tutorial for new users ───
  useEffect(() => {
    if (!keysLoading && tutorialOpen === null) {
      setTutorialOpen(!hasBinanceKey);
    }
  }, [keysLoading, hasBinanceKey, tutorialOpen]);

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
    {
      id: "subscription",
      icon: CreditCard,
      labelKey: "settings.navSubscription",
      descKey: "settings.navSubscriptionDesc",
    },
    {
      id: "security",
      icon: Shield,
      labelKey: "settings.navSecurity",
      descKey: "settings.navSecurityDesc",
    },
  ];

  // ─── Data fetching (parallelized) ───
  useEffect(() => {
    let cancelled = false;

    setProfileLoading(true);
    setProfileError(null);
    setKeysLoading(true);
    setKeysError(null);
    setAutoTradeLoading(true);
    setAutoTradeError(null);
    setSubscriptionLoading(true);

    Promise.allSettled([
      getUserProfile(),
      getApiKeys(),
      getAutoTradeStatus(),
      getSubscriptionStatus(),
    ]).then(([profileResult, keysResult, autoTradeResult, subResult]) => {
      if (cancelled) return;

      // Profile
      if (profileResult.status === "fulfilled") {
        setProfile(profileResult.value);
      } else {
        const err = profileResult.reason;
        setProfileError(
          err instanceof Error ? err.message : t("common.loadFailed")
        );
      }
      setProfileLoading(false);

      // API Keys
      if (keysResult.status === "fulfilled") {
        setApiKeys(keysResult.value);
      } else {
        const err = keysResult.reason;
        setKeysError(
          err instanceof Error ? err.message : t("common.loadFailed")
        );
      }
      setKeysLoading(false);

      // Auto Trade Status
      if (autoTradeResult.status === "fulfilled") {
        setAutoTradeStatus(autoTradeResult.value);
      } else {
        const err = autoTradeResult.reason;
        setAutoTradeError(
          err instanceof Error ? err.message : t("common.loadFailed")
        );
      }
      setAutoTradeLoading(false);

      // Subscription Status
      if (subResult.status === "fulfilled") {
        setSubscriptionActive(subResult.value.active);
      } else {
        setSubscriptionActive(false);
      }
      setSubscriptionLoading(false);
    });

    return () => {
      cancelled = true;
    };
  }, [t]);

  // ─── Handlers ───
  async function handleSaveApiKey() {
    if (!apiKeyInput.trim() || !secretKeyInput.trim()) {
      toast.error(t("settings.fillRequired"));
      return;
    }
    setSaving(true);
    try {
      const result = await saveApiKey({
        exchange,
        apiKey: apiKeyInput.trim(),
        secretKey: secretKeyInput.trim(),
      });
      toast.success(result.message || t("common.saveSuccess"));
      setApiKeyInput("");
      setSecretKeyInput("");
      const updatedKeys = await getApiKeys();
      setApiKeys(updatedKeys);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("common.saveFailed"));
    } finally {
      setSaving(false);
    }
  }

  function handleToggleAutoTrade(enabled: boolean) {
    setAutoTradeConfirmValue(enabled);
    setAutoTradeConfirmOpen(true);
  }

  async function confirmToggleAutoTrade() {
    setAutoTradeConfirmOpen(false);
    setAutoTradeUpdating(true);
    try {
      const result = await updateAutoTradeStatus(autoTradeConfirmValue);
      setAutoTradeStatus(result);
      toast.success(result.message);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("common.saveFailed"));
      setAutoTradeStatus((prev) =>
        prev ? { ...prev, autoTradeEnabled: !autoTradeConfirmValue } : null
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
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
            {[...Array(5)].map((_, i) => (
              <div key={i} className="space-y-2">
                <div className="h-3 w-16 bg-muted animate-pulse rounded" />
                <div className="h-5 w-32 bg-muted animate-pulse rounded" />
              </div>
            ))}
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

  function handleCopyIp() {
    navigator.clipboard.writeText("159.223.85.29").then(() => {
      setIpCopied(true);
      toast.success(t("settings.apiKeyStep3Copied"));
      setTimeout(() => setIpCopied(false), 2000);
    });
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

        {/* ─── API Key Setup Tutorial ─── */}
        <div className="border border-amber-200 dark:border-amber-900 rounded-lg overflow-hidden">
          {/* Tutorial header — clickable toggle */}
          <button
            type="button"
            onClick={() => setTutorialOpen((prev) => !prev)}
            className="w-full flex items-center justify-between px-4 py-3 bg-amber-50 dark:bg-amber-950/30 hover:bg-amber-100 dark:hover:bg-amber-950/50 transition-colors text-left"
          >
            <span className="font-medium text-sm text-amber-800 dark:text-amber-200">
              🔑 {t("settings.apiKeyTutorialTitle")}
            </span>
            {tutorialOpen ? (
              <ChevronUp className="h-4 w-4 text-amber-600 dark:text-amber-400 shrink-0" />
            ) : (
              <ChevronDown className="h-4 w-4 text-amber-600 dark:text-amber-400 shrink-0" />
            )}
          </button>

          {/* Tutorial content — collapsible */}
          <div
            className={cn(
              "grid transition-all duration-300 ease-in-out",
              tutorialOpen ? "grid-rows-[1fr] opacity-100" : "grid-rows-[0fr] opacity-0"
            )}
          >
            <div className="overflow-hidden">
              <div className="px-4 py-4 space-y-4 text-sm text-amber-900 dark:text-amber-100">
                {/* Prerequisite warning */}
                <div className="p-3 bg-amber-100 dark:bg-amber-950/50 border border-amber-300 dark:border-amber-800 rounded-lg">
                  <p className="font-medium">⚠️ {t("settings.apiKeyPrerequisite")}</p>
                </div>

                {/* Steps */}
                <ol className="space-y-3 list-none">
                  {/* Step 1 */}
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-amber-200 dark:bg-amber-800 text-xs font-bold text-amber-800 dark:text-amber-200">1</span>
                    <p className="pt-0.5">{t("settings.apiKeyStep1")}</p>
                  </li>

                  {/* Step 2 — Permissions */}
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-amber-200 dark:bg-amber-800 text-xs font-bold text-amber-800 dark:text-amber-200">2</span>
                    <div className="space-y-1.5 pt-0.5">
                      <p className="font-medium">{t("settings.apiKeyStep2Title")}</p>
                      <div className="space-y-1 text-xs">
                        <p className="text-emerald-600 dark:text-emerald-400">✅ {t("settings.apiKeyStep2Check1")}</p>
                        <p className="text-emerald-600 dark:text-emerald-400">✅ {t("settings.apiKeyStep2Check2")}</p>
                        <p className="text-red-600 dark:text-red-400">❌ {t("settings.apiKeyStep2Warning")}</p>
                      </div>
                    </div>
                  </li>

                  {/* Step 3 — IP whitelist */}
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-amber-200 dark:bg-amber-800 text-xs font-bold text-amber-800 dark:text-amber-200">3</span>
                    <div className="space-y-1.5 pt-0.5">
                      <p className="font-medium">{t("settings.apiKeyStep3Title")}</p>
                      <p className="text-xs text-amber-700 dark:text-amber-300">{t("settings.apiKeyStep3Desc")}</p>
                      <div className="inline-flex items-center gap-2 px-3 py-1.5 bg-white dark:bg-zinc-900 border border-amber-300 dark:border-amber-700 rounded-md font-mono text-sm">
                        <span>159.223.85.29</span>
                        <button
                          type="button"
                          onClick={handleCopyIp}
                          className="p-0.5 rounded hover:bg-amber-100 dark:hover:bg-amber-800 transition-colors"
                          title="Copy IP"
                        >
                          {ipCopied ? (
                            <Check className="h-3.5 w-3.5 text-emerald-500" />
                          ) : (
                            <Copy className="h-3.5 w-3.5 text-amber-600 dark:text-amber-400" />
                          )}
                        </button>
                      </div>
                    </div>
                  </li>

                  {/* Step 4 — Copy keys */}
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-amber-200 dark:bg-amber-800 text-xs font-bold text-amber-800 dark:text-amber-200">4</span>
                    <div className="space-y-1 pt-0.5">
                      <p>{t("settings.apiKeyStep4")}</p>
                      <p className="text-xs text-red-600 dark:text-red-400 font-medium">⚠️ {t("settings.apiKeyStep4Warning")}</p>
                    </div>
                  </li>

                  {/* Step 5 — Save */}
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-amber-200 dark:bg-amber-800 text-xs font-bold text-amber-800 dark:text-amber-200">5</span>
                    <p className="pt-0.5">{t("settings.apiKeyStep5")}</p>
                  </li>
                </ol>
              </div>
            </div>
          </div>
        </div>

        {keysLoading && (
          <div className="space-y-3">
            {[...Array(2)].map((_, i) => (
              <div key={i} className="flex items-center justify-between p-3 border rounded-lg">
                <div className="flex items-center gap-3">
                  <div className="h-5 w-20 bg-muted animate-pulse rounded" />
                  <div className="h-5 w-16 bg-muted animate-pulse rounded-full" />
                </div>
                <div className="h-4 w-24 bg-muted animate-pulse rounded" />
              </div>
            ))}
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
                  <div className="relative">
                    <Input
                      id="apiKey"
                      type={showApiKey ? "text" : "password"}
                      value={apiKeyInput}
                      onChange={(e) => setApiKeyInput(e.target.value)}
                      placeholder={t("settings.apiKeyPlaceholder")}
                      className="pr-10"
                    />
                    <button
                      type="button"
                      onClick={() => setShowApiKey(!showApiKey)}
                      className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted-foreground hover:text-foreground transition-colors"
                    >
                      {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                    </button>
                  </div>
                </div>

                <div className="space-y-2">
                  <Label
                    htmlFor="secretKey"
                    className="text-xs text-muted-foreground"
                  >
                    Secret Key
                  </Label>
                  <div className="relative">
                    <Input
                      id="secretKey"
                      type={showSecretKey ? "text" : "password"}
                      value={secretKeyInput}
                      onChange={(e) => setSecretKeyInput(e.target.value)}
                      placeholder={t("settings.secretKeyPlaceholder")}
                      className="pr-10"
                    />
                    <button
                      type="button"
                      onClick={() => setShowSecretKey(!showSecretKey)}
                      className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted-foreground hover:text-foreground transition-colors"
                    >
                      {showSecretKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                    </button>
                  </div>
                </div>
              </div>

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
          <div className="space-y-4">
            <div className="flex items-center justify-between p-4 border rounded-lg bg-muted/30">
              <div className="space-y-2">
                <div className="h-5 w-32 bg-muted animate-pulse rounded" />
                <div className="h-4 w-48 bg-muted animate-pulse rounded" />
              </div>
              <div className="h-6 w-10 bg-muted animate-pulse rounded-full" />
            </div>
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
                disabled={autoTradeUpdating || prerequisitesLoading || !canEnableAutoTrade}
              />
            </div>

            {/* Prerequisite warning (hidden while dependencies still loading) */}
            {!canEnableAutoTrade && !prerequisitesLoading && (
              <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700 dark:bg-red-950/30 dark:border-red-900 dark:text-red-300 space-y-1">
                <p className="font-medium">
                  🔒 {t("settings.autoTradePrerequisite")}
                </p>
                <ul className="list-disc list-inside text-xs space-y-0.5">
                  {!hasBinanceKey && (
                    <li>❌ {t("settings.autoTradeMissingApiKey")}</li>
                  )}
                  {!subscriptionActive && (
                    <li>❌ {t("settings.autoTradeMissingSubscription")}</li>
                  )}
                </ul>
              </div>
            )}

            {/* Webhook soft reminder */}
            {!hasActiveWebhook && (
              <div className="p-3 bg-blue-50 border border-blue-200 rounded-lg text-sm text-blue-700 dark:bg-blue-950/30 dark:border-blue-900 dark:text-blue-300">
                💡 {t("settings.webhookReminder")}
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

        {/* Trade Parameters */}
        <Separator />
        <div>
          <h2 className="text-lg font-semibold">{t("settings.tradeParams")}</h2>
          <p className="text-sm text-muted-foreground">
            {t("settings.tradeParamsDesc")}
          </p>
        </div>
        <TradeSettingsForm />

        {/* Auto Trade 確認對話框 */}
        <Dialog open={autoTradeConfirmOpen} onOpenChange={setAutoTradeConfirmOpen}>
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle>
                {autoTradeConfirmValue
                  ? t("settings.autoTradeConfirmEnableTitle")
                  : t("settings.autoTradeConfirmDisableTitle")}
              </DialogTitle>
              <DialogDescription asChild>
                <div>
                  <p>{autoTradeConfirmValue
                    ? t("settings.autoTradeConfirmEnableDesc")
                    : t("settings.autoTradeConfirmDisableDesc")}</p>
                  {autoTradeConfirmValue && (
                    <div className="mt-3 space-y-1 text-xs">
                      <p className="font-medium text-foreground">{t("settings.autoTradeConfirmEnablePrereqs")}</p>
                      <p className="text-emerald-500">✓ {t("settings.autoTradeConfirmApiKeyOk")}</p>
                      {hasActiveWebhook ? (
                        <p className="text-emerald-500">✓ {t("settings.autoTradeConfirmWebhookOk")}</p>
                      ) : (
                        <p className="text-yellow-500">⚠ {t("settings.autoTradeConfirmNoWebhook")}</p>
                      )}
                    </div>
                  )}
                </div>
              </DialogDescription>
            </DialogHeader>
            <DialogFooter className="gap-2 sm:gap-0">
              <Button
                variant="outline"
                onClick={() => setAutoTradeConfirmOpen(false)}
              >
                {t("common.cancel")}
              </Button>
              <Button
                variant={autoTradeConfirmValue ? "default" : "destructive"}
                onClick={confirmToggleAutoTrade}
              >
                {autoTradeConfirmValue
                  ? t("settings.autoTradeConfirmEnable")
                  : t("settings.autoTradeConfirmDisable")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    );
  }

  function renderNotifications() {
    return (
      <div className="space-y-6">
        {/* Discord Section */}
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

        {/* LINE Section */}
        <div className="pt-6">
          <h2 className="text-lg font-semibold">
            {t("settings.lineNotification")}
          </h2>
          <p className="text-sm text-muted-foreground">
            {t("settings.lineNotificationDesc")}
          </p>
        </div>
        <Separator />
        <LineBindingManager />
      </div>
    );
  }

  function renderSubscription() {
    return (
      <div className="space-y-6">
        <div>
          <h2 className="text-lg font-semibold">
            {t("settings.subscriptionTitle")}
          </h2>
          <p className="text-sm text-muted-foreground">
            {t("settings.navSubscriptionDesc")}
          </p>
        </div>
        <Separator />
        <SubscriptionManager />
      </div>
    );
  }

  async function handleChangePassword() {
    setPwError("");

    if (newPw.length < 8) {
      setPwError(t("settings.passwordMinLength"));
      return;
    }
    if (newPw !== confirmPw) {
      setPwError(t("settings.passwordMismatch"));
      return;
    }

    setPwSaving(true);
    try {
      await changePassword({
        currentPassword: currentPw,
        newPassword: newPw,
        confirmPassword: confirmPw,
      });
      toast.success(t("settings.passwordChangedToast"));
      setCurrentPw("");
      setNewPw("");
      setConfirmPw("");

      // 2 秒後登出跳轉
      setTimeout(async () => {
        await apiLogout();
        localStorage.removeItem("userId");
        localStorage.removeItem("email");
        window.location.href = "/login";
      }, 2000);
    } catch (err: unknown) {
      if (err instanceof Error) {
        try {
          const parsed = JSON.parse(err.message);
          setPwError(parsed.error || err.message);
        } catch {
          setPwError(err.message);
        }
      } else {
        setPwError(t("common.saveFailed"));
      }
    } finally {
      setPwSaving(false);
    }
  }

  async function handleDeleteAccount() {
    setDeleteLoading(true);
    try {
      await deleteAccount();
      toast.success(t("settings.deleteAccountSuccess"));
      setDeleteDialogOpen(false);

      // 2 秒後登出跳轉
      setTimeout(async () => {
        await apiLogout();
        localStorage.removeItem("userId");
        localStorage.removeItem("email");
        window.location.href = "/login";
      }, 2000);
    } catch (err) {
      toast.error(
        err instanceof Error ? err.message : t("settings.deleteAccountError")
      );
    } finally {
      setDeleteLoading(false);
    }
  }

  function renderSecurity() {
    return (
      <div className="space-y-6">
        <div>
          <h2 className="text-lg font-semibold">{t("settings.changePassword")}</h2>
          <p className="text-sm text-muted-foreground">
            {t("settings.navSecurityDesc")}
          </p>
        </div>
        <Separator />

        {pwError && (
          <div className="rounded-lg bg-red-500/10 border border-red-500/20 px-4 py-2.5 text-sm text-red-400">
            {pwError}
          </div>
        )}

        <div className="space-y-4 max-w-md">
          <div className="space-y-2">
            <Label htmlFor="currentPw" className="text-sm text-muted-foreground">
              {t("settings.currentPassword")}
            </Label>
            <Input
              id="currentPw"
              type="password"
              value={currentPw}
              onChange={(e) => setCurrentPw(e.target.value)}
              placeholder="••••••••"
              autoComplete="current-password"
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="newPw" className="text-sm text-muted-foreground">
              {t("settings.newPassword")}
            </Label>
            <Input
              id="newPw"
              type="password"
              value={newPw}
              onChange={(e) => setNewPw(e.target.value)}
              placeholder="••••••••"
              minLength={8}
              autoComplete="new-password"
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="confirmPw" className="text-sm text-muted-foreground">
              {t("settings.confirmPassword")}
            </Label>
            <Input
              id="confirmPw"
              type="password"
              value={confirmPw}
              onChange={(e) => setConfirmPw(e.target.value)}
              placeholder="••••••••"
              minLength={8}
              autoComplete="new-password"
            />
          </div>

          <Button
            onClick={handleChangePassword}
            disabled={pwSaving || !currentPw || !newPw || !confirmPw}
          >
            {pwSaving ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                {t("settings.changingPassword")}
              </>
            ) : (
              t("settings.changePasswordButton")
            )}
          </Button>
        </div>

        {/* ─── Danger Zone: Delete Account ─── */}
        <Separator />
        <div className="rounded-lg border border-red-300 dark:border-red-900 bg-red-50 dark:bg-red-950/20 p-5 space-y-3">
          <div className="flex items-center gap-2">
            <AlertTriangle className="h-5 w-5 text-red-500" />
            <h3 className="text-base font-semibold text-red-700 dark:text-red-400">
              {t("settings.dangerZone")}
            </h3>
          </div>
          <div>
            <h4 className="text-sm font-medium text-red-700 dark:text-red-300">
              {t("settings.deleteAccountTitle")}
            </h4>
            <p className="text-sm text-red-600/80 dark:text-red-400/80 mt-1">
              {t("settings.deleteAccountDesc")}
            </p>
          </div>
          <Button
            variant="destructive"
            onClick={() => setDeleteDialogOpen(true)}
          >
            {t("settings.deleteAccountButton")}
          </Button>
        </div>

        {/* Delete Account Confirmation Dialog */}
        <Dialog
          open={deleteDialogOpen}
          onOpenChange={(open) => {
            setDeleteDialogOpen(open);
            if (!open) setDeleteConfirmText("");
          }}
        >
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2 text-red-600 dark:text-red-400">
                <AlertTriangle className="h-5 w-5" />
                {t("settings.deleteAccountConfirmTitle")}
              </DialogTitle>
              <DialogDescription>
                {t("settings.deleteAccountConfirmDesc")}
              </DialogDescription>
            </DialogHeader>

            <div className="space-y-3 py-2">
              <Label htmlFor="deleteConfirm" className="text-sm">
                {t("settings.deleteAccountConfirmPrompt")}
              </Label>
              <Input
                id="deleteConfirm"
                value={deleteConfirmText}
                onChange={(e) => setDeleteConfirmText(e.target.value)}
                placeholder={t("settings.deleteAccountConfirmPlaceholder")}
                className="font-mono"
                autoComplete="off"
              />
            </div>

            <DialogFooter className="gap-2 sm:gap-0">
              <Button
                variant="outline"
                onClick={() => {
                  setDeleteDialogOpen(false);
                  setDeleteConfirmText("");
                }}
              >
                {t("common.cancel")}
              </Button>
              <Button
                variant="destructive"
                onClick={handleDeleteAccount}
                disabled={deleteConfirmText !== "DELETE" || deleteLoading}
              >
                {deleteLoading ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    {t("settings.deleteAccountDeleting")}
                  </>
                ) : (
                  t("settings.deleteAccountButton")
                )}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    );
  }

  const sectionRenderers: Record<SettingsSection, () => React.ReactNode> = {
    profile: renderProfile,
    "api-keys": renderApiKeys,
    trading: renderTrading,
    notifications: renderNotifications,
    subscription: renderSubscription,
    security: renderSecurity,
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

          {/* Mobile: horizontal scroll nav with fade indicators */}
          <div className="relative lg:hidden">
            <div className="flex gap-2 overflow-x-auto pb-2 scrollbar-hide">
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
            {/* Fade indicators */}
            <div className="absolute right-0 top-0 bottom-2 w-8 bg-gradient-to-l from-background to-transparent pointer-events-none" />
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
