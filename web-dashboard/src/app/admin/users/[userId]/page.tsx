"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useT } from "@/lib/i18n/i18n-context";
import { getAdminUserDetail, updateAdminUser, adminSendNotification, adminSetApiKey, adminUpdateTradeSettings } from "@/lib/api";
import type { UpdateTradeSettingsRequest } from "@/types";
import type { AdminUserDetailResponse } from "@/types";
import {
  ArrowLeft, Check, X, Power, Shield, Mail, Key, Bell,
  TrendingUp, MessageSquare, Link2, Send, Edit2,
} from "lucide-react";
import { toast } from "sonner";

export default function AdminUserDetailPage() {
  const { t } = useT();
  const router = useRouter();
  const params = useParams();
  const userId = params.userId as string;

  const [data, setData] = useState<AdminUserDetailResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [notifDialogOpen, setNotifDialogOpen] = useState(false);

  // API Key form state
  const [apiKeyFormOpen, setApiKeyFormOpen] = useState(false);
  const [apiKeyInput, setApiKeyInput] = useState("");
  const [secretKeyInput, setSecretKeyInput] = useState("");
  const [savingApiKey, setSavingApiKey] = useState(false);

  // Trade Settings edit state
  const [editingTradeSettings, setEditingTradeSettings] = useState(false);
  const [tsForm, setTsForm] = useState<UpdateTradeSettingsRequest>({});
  const [savingTs, setSavingTs] = useState(false);

  useEffect(() => {
    setLoading(true);
    getAdminUserDetail(userId)
      .then(setData)
      .catch(() => setError(true))
      .finally(() => setLoading(false));
  }, [userId]);

  async function handleToggle(field: "enabled" | "autoTradeEnabled") {
    if (!data) return;
    setUpdating(true);
    try {
      await updateAdminUser(userId, { [field]: !data[field] });
      toast.success(t("admin.updateSuccess"));
      // Refetch
      const updated = await getAdminUserDetail(userId);
      setData(updated);
    } catch {
      toast.error(t("admin.updateFailed"));
    } finally {
      setUpdating(false);
    }
  }

  async function handleRoleChange() {
    if (!data) return;
    const newRole = data.role === "ADMIN" ? "USER" : "ADMIN";
    setUpdating(true);
    try {
      await updateAdminUser(userId, { role: newRole });
      toast.success(t("admin.updateSuccess"));
      const updated = await getAdminUserDetail(userId);
      setData(updated);
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : t("admin.updateFailed"));
    } finally {
      setUpdating(false);
    }
  }

  async function handleSaveApiKey() {
    if (!apiKeyInput.trim() || !secretKeyInput.trim()) return;
    setSavingApiKey(true);
    try {
      await adminSetApiKey(userId, {
        exchange: "BINANCE",
        apiKey: apiKeyInput.trim(),
        secretKey: secretKeyInput.trim(),
      });
      toast.success(t("admin.apiKeySaveSuccess"));
      setApiKeyFormOpen(false);
      setApiKeyInput("");
      setSecretKeyInput("");
      const updated = await getAdminUserDetail(userId);
      setData(updated);
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : t("admin.updateFailed"));
    } finally {
      setSavingApiKey(false);
    }
  }

  function openTradeSettingsEdit() {
    if (!data) return;
    setTsForm({
      riskPercent: data.tradeSettings.riskPercent,
      maxLeverage: data.tradeSettings.maxLeverage,
      maxDcaLayers: data.tradeSettings.maxDcaLayers,
      maxPositionSizeUsdt: data.tradeSettings.maxPositionSizeUsdt,
      dailyLossLimitUsdt: data.tradeSettings.dailyLossLimitUsdt ?? 0,
      dcaRiskMultiplier: data.tradeSettings.dcaRiskMultiplier ?? 0,
      dailyLossPercent: data.tradeSettings.dailyLossPercent ?? 0,
      maxPositionPercent: data.tradeSettings.maxPositionPercent ?? 0,
      autoSlEnabled: data.tradeSettings.autoSlEnabled,
      autoTpEnabled: data.tradeSettings.autoTpEnabled,
      allowedSymbols: data.tradeSettings.allowedSymbols,
    });
    setEditingTradeSettings(true);
  }

  async function handleSaveTradeSettings() {
    setSavingTs(true);
    try {
      await adminUpdateTradeSettings(userId, tsForm);
      toast.success(t("admin.tradeSettingsSaveSuccess"));
      setEditingTradeSettings(false);
      const updated = await getAdminUserDetail(userId);
      setData(updated);
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : t("admin.updateFailed"));
    } finally {
      setSavingTs(false);
    }
  }

  if (loading) {
    return (
      <div className="flex h-[60vh] items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="space-y-4">
        <button onClick={() => router.push("/admin/users")} className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="h-4 w-4" /> {t("admin.backToUsers")}
        </button>
        <div className="flex h-[40vh] items-center justify-center text-muted-foreground">
          {t("admin.loadFailed")}
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <button onClick={() => router.push("/admin/users")} className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground mb-3">
          <ArrowLeft className="h-4 w-4" /> {t("admin.backToUsers")}
        </button>
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-bold">{data.name || data.email || data.userId}</h1>
          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
            data.role === "ADMIN" ? "bg-purple-500/20 text-purple-400" : "bg-blue-500/20 text-blue-400"
          }`}>
            {data.role}
          </span>
          {data.enabled ? (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-green-500/20 text-green-400">Active</span>
          ) : (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-red-500/20 text-red-400">Disabled</span>
          )}
        </div>
        <div className="text-sm text-muted-foreground mt-1">
          {data.email && <span>{data.email}</span>}
          {data.createdAt && <span className="ml-3">{t("admin.createdAt")}: {new Date(data.createdAt).toLocaleDateString()}</span>}
        </div>
      </div>

      {/* Account Info */}
      <Section icon={Shield} title={t("admin.accountInfo")}>
        <InfoGrid items={[
          { label: "Email", value: data.email || "-" },
          { label: t("admin.name"), value: data.name || "-" },
          { label: t("admin.role"), value: data.role },
          { label: t("admin.emailVerified"), value: <BoolBadge value={data.emailVerified} t={t} /> },
          { label: t("admin.hasPassword"), value: <BoolBadge value={data.hasPassword} t={t} /> },
          { label: t("admin.passwordChangedAt"), value: data.passwordChangedAt ? new Date(data.passwordChangedAt).toLocaleString() : "-" },
          { label: t("admin.createdAt"), value: data.createdAt ? new Date(data.createdAt).toLocaleString() : "-" },
          { label: "Updated", value: data.updatedAt ? new Date(data.updatedAt).toLocaleString() : "-" },
        ]} />
      </Section>

      {/* Login Methods */}
      <Section icon={Key} title={t("admin.loginMethods")}>
        <div className="flex gap-2 flex-wrap">
          {data.loginMethods.length === 0 && <span className="text-sm text-muted-foreground">{t("admin.noData")}</span>}
          {data.loginMethods.map((m) => (
            <span key={m} className={`inline-flex items-center px-2.5 py-1 rounded-lg text-xs font-medium ${
              m === "LINE" ? "bg-green-500/15 text-green-400"
              : m === "GOOGLE" ? "bg-blue-500/15 text-blue-400"
              : "bg-gray-500/15 text-gray-400"
            }`}>
              {m} <Check className="h-3 w-3 ml-1" />
            </span>
          ))}
        </div>
        {data.oauthProviders.length > 0 && (
          <div className="mt-4">
            <h4 className="text-xs font-medium text-muted-foreground mb-2">{t("admin.oauthProviders")}</h4>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-xs border-b border-border/50">
                    <th className="text-left py-1.5 pr-4">{t("admin.provider")}</th>
                    <th className="text-left py-1.5 pr-4">{t("admin.displayName")}</th>
                    <th className="text-left py-1.5 pr-4">Email</th>
                    <th className="text-left py-1.5">{t("admin.createdAt")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.oauthProviders.map((p, i) => (
                    <tr key={i} className="border-b border-border/30">
                      <td className="py-1.5 pr-4 font-medium">{p.provider}</td>
                      <td className="py-1.5 pr-4">{p.displayName || "-"}</td>
                      <td className="py-1.5 pr-4">{p.email || "-"}</td>
                      <td className="py-1.5 text-muted-foreground">{p.createdAt ? new Date(p.createdAt).toLocaleDateString() : "-"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </Section>

      {/* Notification Settings */}
      <Section icon={Bell} title={t("admin.notificationSettings")}>
        {/* LINE Binding */}
        <div className="mb-4">
          <h4 className="text-xs font-medium text-muted-foreground mb-2">{t("admin.lineBinding")}</h4>
          {data.lineBinding ? (
            <div className="flex items-center gap-4 text-sm">
              <span className="text-green-400 font-medium">{t("admin.bound")}</span>
              <span>{t("admin.displayName")}: {data.lineBinding.displayName || "-"}</span>
              {data.lineBinding.linkedAt && <span className="text-muted-foreground">{t("admin.linkedAt")}: {new Date(data.lineBinding.linkedAt).toLocaleDateString()}</span>}
            </div>
          ) : (
            <span className="text-sm text-muted-foreground">{t("admin.notBound")}</span>
          )}
        </div>

        {/* Discord Webhooks */}
        {data.discordWebhooks.length > 0 && (
          <div className="mb-4">
            <h4 className="text-xs font-medium text-muted-foreground mb-2">{t("admin.discordWebhooks")}</h4>
            <div className="space-y-1.5">
              {data.discordWebhooks.map((w) => (
                <div key={w.webhookId} className="flex items-center gap-3 text-sm">
                  <MessageSquare className="h-3.5 w-3.5 text-muted-foreground" />
                  <span className="font-medium">{w.name || "Unnamed"}</span>
                  <code className="text-xs text-muted-foreground bg-muted px-1.5 py-0.5 rounded">{w.webhookUrlPreview}</code>
                  {w.enabled ? <Check className="h-3.5 w-3.5 text-green-500" /> : <X className="h-3.5 w-3.5 text-red-500" />}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Notification Preferences */}
        {data.notificationPreferences ? (
          <div>
            <h4 className="text-xs font-medium text-muted-foreground mb-2">Preferences</h4>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
              {([
                ["tradeExecution", t("admin.tradeExecution")],
                ["slTpTriggered", t("admin.slTpTriggered")],
                ["protectionLost", t("admin.protectionLost")],
                ["dailyReport", t("admin.dailyReport")],
                ["streamStatus", t("admin.streamStatusNotif")],
                ["systemAlert", t("admin.systemAlertNotif")],
              ] as const).map(([key, label]) => (
                <div key={key} className="flex items-center gap-2 text-sm">
                  <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${
                    data.notificationPreferences![key as keyof typeof data.notificationPreferences]
                      ? "bg-green-500/15 text-green-400"
                      : "bg-gray-500/15 text-gray-500"
                  }`}>
                    {data.notificationPreferences![key as keyof typeof data.notificationPreferences] ? t("admin.on") : t("admin.off")}
                  </span>
                  <span>{label}</span>
                </div>
              ))}
            </div>
          </div>
        ) : (
          <span className="text-sm text-muted-foreground">{t("admin.noData")}</span>
        )}
      </Section>

      {/* API Keys */}
      <Section icon={Link2} title={t("admin.apiKeysSection")} action={
        !apiKeyFormOpen && (
          <button
            onClick={() => setApiKeyFormOpen(true)}
            className="text-xs font-medium text-primary hover:text-primary/80 transition-colors"
          >
            {t("admin.setApiKey")}
          </button>
        )
      }>
        {/* Status */}
        {data.apiKeys.length > 0 ? (
          <div className="space-y-1.5">
            {data.apiKeys.map((k, i) => (
              <div key={i} className="flex items-center gap-3 text-sm">
                <span className="font-medium">{k.exchange}</span>
                <span className="inline-flex items-center gap-1 text-xs px-1.5 py-0.5 rounded bg-green-500/15 text-green-400">
                  <Check className="h-3 w-3" /> {t("admin.apiKeyConfigured")}
                </span>
                <span className="text-muted-foreground text-xs">
                  {k.updatedAt ? new Date(k.updatedAt).toLocaleString() : k.createdAt ? new Date(k.createdAt).toLocaleString() : ""}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <span className="text-sm text-muted-foreground">{t("admin.apiKeyNotConfigured")}</span>
        )}

        {/* Inline Form */}
        {apiKeyFormOpen && (
          <div className="mt-4 p-4 rounded-lg border border-border bg-background space-y-3">
            {data.apiKeys.length > 0 && (
              <div className="flex items-center gap-2 text-xs text-yellow-400 bg-yellow-500/10 px-3 py-2 rounded-lg">
                {t("admin.apiKeyReplaceWarning")}
              </div>
            )}
            <div>
              <label className="block text-xs font-medium text-muted-foreground mb-1">API Key</label>
              <input
                type="password"
                value={apiKeyInput}
                onChange={(e) => setApiKeyInput(e.target.value)}
                autoComplete="off"
                className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-muted-foreground mb-1">Secret Key</label>
              <input
                type="password"
                value={secretKeyInput}
                onChange={(e) => setSecretKeyInput(e.target.value)}
                autoComplete="off"
                className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
              />
            </div>
            <div className="flex gap-2">
              <button
                onClick={() => { setApiKeyFormOpen(false); setApiKeyInput(""); setSecretKeyInput(""); }}
                className="px-4 py-2 rounded-lg border border-border text-sm font-medium hover:bg-accent transition-colors"
              >
                {t("admin.cancel")}
              </button>
              <button
                onClick={handleSaveApiKey}
                disabled={!apiKeyInput.trim() || !secretKeyInput.trim() || savingApiKey}
                className="px-4 py-2 rounded-lg bg-primary text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              >
                {savingApiKey ? "..." : t("admin.save")}
              </button>
            </div>
          </div>
        )}
      </Section>

      {/* Trade Settings */}
      <Section icon={TrendingUp} title={t("admin.tradeSettingsSection")} action={
        !editingTradeSettings && (
          <button
            onClick={openTradeSettingsEdit}
            className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:text-primary/80 transition-colors"
          >
            <Edit2 className="h-3 w-3" /> {t("admin.editTradeSettings")}
          </button>
        )
      }>
        {editingTradeSettings ? (
          <div className="space-y-4">
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-x-6 gap-y-3">
              <NumberField label={t("admin.riskPercent")} value={tsForm.riskPercent} onChange={(v) => setTsForm(p => ({ ...p, riskPercent: v }))} step={0.01} />
              <NumberField label={t("admin.maxLeverage")} value={tsForm.maxLeverage} onChange={(v) => setTsForm(p => ({ ...p, maxLeverage: v }))} step={1} />
              <NumberField label={t("admin.maxDcaLayers")} value={tsForm.maxDcaLayers} onChange={(v) => setTsForm(p => ({ ...p, maxDcaLayers: v }))} step={1} />
              <NumberField label={t("admin.maxPositionSize")} value={tsForm.maxPositionSizeUsdt} onChange={(v) => setTsForm(p => ({ ...p, maxPositionSizeUsdt: v }))} step={100} />
              <NumberField label={t("admin.dailyLossLimit")} value={tsForm.dailyLossLimitUsdt} onChange={(v) => setTsForm(p => ({ ...p, dailyLossLimitUsdt: v }))} step={100} />
              <NumberField label={t("admin.dcaRiskMultiplier")} value={tsForm.dcaRiskMultiplier} onChange={(v) => setTsForm(p => ({ ...p, dcaRiskMultiplier: v }))} step={0.1} />
              <NumberField label={t("admin.dailyLossPercent")} value={tsForm.dailyLossPercent} onChange={(v) => setTsForm(p => ({ ...p, dailyLossPercent: v }))} step={0.01} />
              <NumberField label={t("admin.maxPositionPercent")} value={tsForm.maxPositionPercent} onChange={(v) => setTsForm(p => ({ ...p, maxPositionPercent: v }))} step={0.01} />
            </div>
            <div className="flex items-center gap-6">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={tsForm.autoSlEnabled ?? false} onChange={(e) => setTsForm(p => ({ ...p, autoSlEnabled: e.target.checked }))} className="rounded" />
                {t("admin.autoSl")}
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={tsForm.autoTpEnabled ?? false} onChange={(e) => setTsForm(p => ({ ...p, autoTpEnabled: e.target.checked }))} className="rounded" />
                {t("admin.autoTp")}
              </label>
            </div>
            <div>
              <label className="block text-xs font-medium text-muted-foreground mb-1">{t("admin.allowedSymbols")}</label>
              <input
                type="text"
                value={(tsForm.allowedSymbols ?? []).join(", ")}
                onChange={(e) => setTsForm(p => ({ ...p, allowedSymbols: e.target.value.split(",").map(s => s.trim()).filter(Boolean) }))}
                className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
                placeholder="BTCUSDT, ETHUSDT"
              />
            </div>
            <div className="flex gap-2">
              <button
                onClick={() => setEditingTradeSettings(false)}
                className="px-4 py-2 rounded-lg border border-border text-sm font-medium hover:bg-accent transition-colors"
              >
                {t("admin.cancel")}
              </button>
              <button
                onClick={handleSaveTradeSettings}
                disabled={savingTs}
                className="px-4 py-2 rounded-lg bg-primary text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              >
                {savingTs ? "..." : t("admin.save")}
              </button>
            </div>
          </div>
        ) : (
          <>
            <InfoGrid items={[
              { label: t("admin.riskPercent"), value: data.tradeSettings.riskPercent != null ? `${data.tradeSettings.riskPercent}%` : "-" },
              { label: t("admin.maxLeverage"), value: data.tradeSettings.maxLeverage != null ? `${data.tradeSettings.maxLeverage}x` : "-" },
              { label: t("admin.maxDcaLayers"), value: data.tradeSettings.maxDcaLayers ?? "-" },
              { label: t("admin.maxPositionSize"), value: data.tradeSettings.maxPositionSizeUsdt != null ? `$${data.tradeSettings.maxPositionSizeUsdt}` : "-" },
              { label: t("admin.dailyLossLimit"), value: data.tradeSettings.dailyLossLimitUsdt != null ? `$${data.tradeSettings.dailyLossLimitUsdt}` : "-" },
              { label: t("admin.dcaRiskMultiplier"), value: data.tradeSettings.dcaRiskMultiplier ?? "-" },
              { label: t("admin.dailyLossPercent"), value: data.tradeSettings.dailyLossPercent != null ? `${data.tradeSettings.dailyLossPercent}%` : "-" },
              { label: t("admin.maxPositionPercent"), value: data.tradeSettings.maxPositionPercent != null ? `${data.tradeSettings.maxPositionPercent}%` : "-" },
              { label: t("admin.autoSl"), value: <BoolBadge value={data.tradeSettings.autoSlEnabled} t={t} /> },
              { label: t("admin.autoTp"), value: <BoolBadge value={data.tradeSettings.autoTpEnabled} t={t} /> },
            ]} />
            {data.tradeSettings.allowedSymbols && data.tradeSettings.allowedSymbols.length > 0 && (
              <div className="mt-3">
                <span className="text-xs font-medium text-muted-foreground">{t("admin.allowedSymbols")}:</span>
                <div className="flex gap-1.5 flex-wrap mt-1">
                  {data.tradeSettings.allowedSymbols.map((s) => (
                    <span key={s} className="inline-block px-2 py-0.5 rounded bg-accent text-xs font-medium">{s}</span>
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </Section>

      {/* Operations */}
      <Section icon={Mail} title={t("admin.operationsSection")}>
        <div className="flex flex-wrap gap-3">
          <ActionButton
            onClick={() => handleToggle("enabled")}
            disabled={updating}
            variant={data.enabled ? "danger" : "success"}
            icon={Power}
            label={data.enabled ? t("admin.disableAccount") : t("admin.enableAccount")}
          />
          <ActionButton
            onClick={() => handleToggle("autoTradeEnabled")}
            disabled={updating}
            variant={data.autoTradeEnabled ? "danger" : "success"}
            icon={TrendingUp}
            label={data.autoTradeEnabled ? t("admin.disableAutoTrade") : t("admin.enableAutoTrade")}
          />
          <ActionButton
            onClick={handleRoleChange}
            disabled={updating}
            variant="neutral"
            icon={Shield}
            label={`${t("admin.changeRole")} → ${data.role === "ADMIN" ? "USER" : "ADMIN"}`}
          />
          <ActionButton
            onClick={() => setNotifDialogOpen(true)}
            disabled={updating}
            variant="neutral"
            icon={Send}
            label={t("adminNotif.sendToUser")}
          />
        </div>
      </Section>

      {/* Send Notification Dialog */}
      {notifDialogOpen && (
        <SendNotifDialog
          userId={userId}
          userName={data.name || data.email || ""}
          onClose={() => setNotifDialogOpen(false)}
        />
      )}
    </div>
  );
}

// ==================== Sub-components ====================

function Section({ icon: Icon, title, action, children }: { icon: React.ComponentType<{ className?: string }>; title: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <Icon className="h-4 w-4 text-muted-foreground" />
          <h2 className="text-sm font-semibold">{title}</h2>
        </div>
        {action}
      </div>
      {children}
    </div>
  );
}

function NumberField({ label, value, onChange, step }: {
  label: string;
  value: number | undefined;
  onChange: (v: number) => void;
  step: number;
}) {
  return (
    <div>
      <label className="block text-xs text-muted-foreground mb-1">{label}</label>
      <input
        type="number"
        value={value ?? ""}
        onChange={(e) => onChange(Number(e.target.value))}
        step={step}
        className="w-full rounded-lg border border-border bg-background px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
      />
    </div>
  );
}

function InfoGrid({ items }: { items: { label: string; value: React.ReactNode }[] }) {
  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-x-6 gap-y-3">
      {items.map((item, i) => (
        <div key={i}>
          <div className="text-xs text-muted-foreground mb-0.5">{item.label}</div>
          <div className="text-sm font-medium">{item.value}</div>
        </div>
      ))}
    </div>
  );
}

function BoolBadge({ value, t }: { value: boolean; t: (key: string) => string }) {
  return value ? (
    <span className="inline-flex items-center gap-1 text-green-400"><Check className="h-3.5 w-3.5" /> {t("admin.yes")}</span>
  ) : (
    <span className="inline-flex items-center gap-1 text-muted-foreground"><X className="h-3.5 w-3.5" /> {t("admin.no")}</span>
  );
}

function ActionButton({ onClick, disabled, variant, icon: Icon, label }: {
  onClick: () => void;
  disabled: boolean;
  variant: "danger" | "success" | "neutral";
  icon: React.ComponentType<{ className?: string }>;
  label: string;
}) {
  const colors = {
    danger: "border-red-500/30 text-red-400 hover:bg-red-500/10",
    success: "border-green-500/30 text-green-400 hover:bg-green-500/10",
    neutral: "border-border text-foreground hover:bg-accent",
  };
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`inline-flex items-center gap-2 px-4 py-2 rounded-lg border text-sm font-medium transition-colors disabled:opacity-50 ${colors[variant]}`}
    >
      <Icon className="h-4 w-4" />
      {label}
    </button>
  );
}

function SendNotifDialog({ userId, userName, onClose }: {
  userId: string;
  userName: string;
  onClose: () => void;
}) {
  const { t } = useT();
  const [title, setTitle] = useState("");
  const [message, setMessage] = useState("");
  const [color, setColor] = useState<"BLUE" | "GREEN" | "YELLOW" | "RED">("BLUE");
  const [sending, setSending] = useState(false);

  const colorOptions = [
    { value: "BLUE" as const, label: t("adminNotif.colorBlue"), dot: "bg-blue-400" },
    { value: "GREEN" as const, label: t("adminNotif.colorGreen"), dot: "bg-green-400" },
    { value: "YELLOW" as const, label: t("adminNotif.colorYellow"), dot: "bg-yellow-400" },
    { value: "RED" as const, label: t("adminNotif.colorRed"), dot: "bg-red-400" },
  ];

  async function handleSend() {
    if (!title.trim() || !message.trim()) return;
    setSending(true);
    try {
      const result = await adminSendNotification({
        userIds: [userId],
        title: title.trim(),
        message: message.trim(),
        color,
      });
      if (result.successCount > 0) {
        toast.success(t("adminNotif.success"));
      } else {
        toast.error(t("adminNotif.failed"));
      }
      onClose();
    } catch {
      toast.error(t("adminNotif.failed"));
    } finally {
      setSending(false);
    }
  }

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-40 bg-black/50" onClick={onClose} />
      {/* Dialog */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div className="w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-lg space-y-4">
          <h3 className="text-lg font-semibold">{t("adminNotif.dialogTitle")}</h3>
          <p className="text-sm text-muted-foreground">{userName}</p>

          <div>
            <label className="block text-sm font-medium mb-1">{t("adminNotif.notifTitle")}</label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={t("adminNotif.notifTitlePlaceholder")}
              maxLength={100}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
              autoFocus
            />
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">{t("adminNotif.notifMessage")}</label>
            <textarea
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder={t("adminNotif.notifMessagePlaceholder")}
              maxLength={2000}
              rows={4}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 resize-none"
            />
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">{t("adminNotif.color")}</label>
            <div className="flex flex-wrap gap-2">
              {colorOptions.map((opt) => (
                <button
                  key={opt.value}
                  onClick={() => setColor(opt.value)}
                  className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md border text-xs font-medium transition-colors ${
                    color === opt.value
                      ? "border-primary bg-primary/10 text-foreground"
                      : "border-border text-muted-foreground hover:text-foreground"
                  }`}
                >
                  <span className={`h-2 w-2 rounded-full ${opt.dot}`} />
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          <div className="flex gap-3 pt-2">
            <button
              onClick={onClose}
              className="flex-1 rounded-lg border border-border px-4 py-2 text-sm font-medium hover:bg-accent transition-colors"
            >
              {t("common.cancel")}
            </button>
            <button
              onClick={handleSend}
              disabled={!title.trim() || !message.trim() || sending}
              className="flex-1 flex items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
            >
              <Send className="h-4 w-4" />
              {sending ? t("adminNotif.sending") : t("adminNotif.send")}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
