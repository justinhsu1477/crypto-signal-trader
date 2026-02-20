"use client";

import { useEffect, useState } from "react";
import type { UserTradeSettings } from "@/types";
import { getTradeSettings, updateTradeSettings } from "@/lib/api";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { useT } from "@/lib/i18n/i18n-context";

export function TradeSettingsForm() {
  const { t } = useT();

  // Data state
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [riskPercent, setRiskPercent] = useState("");
  const [maxLeverage, setMaxLeverage] = useState("");
  const [maxDcaLayers, setMaxDcaLayers] = useState("");
  const [maxPositionSize, setMaxPositionSize] = useState("");
  const [allowedSymbols, setAllowedSymbols] = useState("");
  const [autoSlEnabled, setAutoSlEnabled] = useState(true);
  const [autoTpEnabled, setAutoTpEnabled] = useState(true);

  // Save state
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);

  // Fetch settings on mount
  useEffect(() => {
    let cancelled = false;

    async function fetchSettings() {
      setLoading(true);
      setError(null);
      try {
        const data = await getTradeSettings();
        if (!cancelled) {
          setRiskPercent(
            data.riskPercent != null
              ? String(Math.round(data.riskPercent * 100))
              : "20"
          );
          setMaxLeverage(
            data.maxLeverage != null ? String(data.maxLeverage) : "20"
          );
          setMaxDcaLayers(
            data.maxDcaLayers != null ? String(data.maxDcaLayers) : "3"
          );
          setMaxPositionSize(
            data.maxPositionSizeUsdt != null
              ? String(data.maxPositionSizeUsdt)
              : "50000"
          );
          setAllowedSymbols(
            data.allowedSymbols ? data.allowedSymbols.join(", ") : "BTCUSDT"
          );
          setAutoSlEnabled(data.autoSlEnabled ?? true);
          setAutoTpEnabled(data.autoTpEnabled ?? true);
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error ? err.message : t("common.loadFailed")
          );
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    fetchSettings();
    return () => {
      cancelled = true;
    };
  }, [t]);

  async function handleSave() {
    setSaving(true);
    setMessage(null);
    try {
      const riskVal = parseFloat(riskPercent);
      const leverageVal = parseInt(maxLeverage, 10);
      const dcaVal = parseInt(maxDcaLayers, 10);
      const positionVal = parseFloat(maxPositionSize);

      // Basic validation
      if (isNaN(riskVal) || riskVal < 1 || riskVal > 100) {
        throw new Error(t("settings.riskPercent") + ": 1-100");
      }
      if (isNaN(leverageVal) || leverageVal < 1 || leverageVal > 125) {
        throw new Error(t("settings.maxLeverage") + ": 1-125");
      }
      if (isNaN(dcaVal) || dcaVal < 0 || dcaVal > 10) {
        throw new Error(t("settings.maxDcaLayers") + ": 0-10");
      }
      if (isNaN(positionVal) || positionVal < 100) {
        throw new Error(t("settings.maxPositionSize") + ": >= 100");
      }

      const symbols = allowedSymbols
        .split(",")
        .map((s) => s.trim().toUpperCase())
        .filter((s) => s.length > 0);

      await updateTradeSettings({
        riskPercent: riskVal / 100, // Convert percentage to decimal
        maxLeverage: leverageVal,
        maxDcaLayers: dcaVal,
        maxPositionSizeUsdt: positionVal,
        allowedSymbols: symbols,
        autoSlEnabled,
        autoTpEnabled,
      });

      setMessage({ type: "success", text: t("common.saveSuccess") });
    } catch (err) {
      setMessage({
        type: "error",
        text: err instanceof Error ? err.message : t("common.saveFailed"),
      });
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return (
      <div className="flex justify-center py-8">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  if (error) {
    return <div className="text-red-500 py-4">{error}</div>;
  }

  return (
    <div className="space-y-6">
      {/* Risk Percent */}
      <div className="space-y-2">
        <Label htmlFor="riskPercent">{t("settings.riskPercent")}</Label>
        <div className="flex items-center gap-2">
          <Input
            id="riskPercent"
            type="number"
            min={1}
            max={100}
            step={1}
            value={riskPercent}
            onChange={(e) => setRiskPercent(e.target.value)}
            className="max-w-[120px]"
          />
          <span className="text-sm text-muted-foreground">%</span>
        </div>
        <p className="text-xs text-muted-foreground">
          {t("settings.riskPercentDesc")}
        </p>
      </div>

      {/* Max Leverage */}
      <div className="space-y-2">
        <Label htmlFor="maxLeverage">{t("settings.maxLeverage")}</Label>
        <div className="flex items-center gap-2">
          <Input
            id="maxLeverage"
            type="number"
            min={1}
            max={125}
            step={1}
            value={maxLeverage}
            onChange={(e) => setMaxLeverage(e.target.value)}
            className="max-w-[120px]"
          />
          <span className="text-sm text-muted-foreground">x</span>
        </div>
        <p className="text-xs text-muted-foreground">
          {t("settings.maxLeverageDesc")}
        </p>
      </div>

      {/* Max DCA Layers */}
      <div className="space-y-2">
        <Label htmlFor="maxDcaLayers">{t("settings.maxDcaLayers")}</Label>
        <Input
          id="maxDcaLayers"
          type="number"
          min={0}
          max={10}
          step={1}
          value={maxDcaLayers}
          onChange={(e) => setMaxDcaLayers(e.target.value)}
          className="max-w-[120px]"
        />
        <p className="text-xs text-muted-foreground">
          {t("settings.maxDcaLayersDesc")}
        </p>
      </div>

      {/* Max Position Size */}
      <div className="space-y-2">
        <Label htmlFor="maxPositionSize">
          {t("settings.maxPositionSize")}
        </Label>
        <div className="flex items-center gap-2">
          <Input
            id="maxPositionSize"
            type="number"
            min={100}
            step={100}
            value={maxPositionSize}
            onChange={(e) => setMaxPositionSize(e.target.value)}
            className="max-w-[200px]"
          />
          <span className="text-sm text-muted-foreground">USDT</span>
        </div>
        <p className="text-xs text-muted-foreground">
          {t("settings.maxPositionSizeDesc")}
        </p>
      </div>

      {/* Allowed Symbols */}
      <div className="space-y-2">
        <Label htmlFor="allowedSymbols">{t("settings.allowedSymbols")}</Label>
        <Input
          id="allowedSymbols"
          type="text"
          placeholder={t("settings.allowedSymbolsPlaceholder")}
          value={allowedSymbols}
          onChange={(e) => setAllowedSymbols(e.target.value)}
          className="max-w-[400px]"
        />
        <p className="text-xs text-muted-foreground">
          {t("settings.allowedSymbolsDesc")}
        </p>
      </div>

      {/* Auto SL */}
      <div className="flex items-center justify-between max-w-[400px]">
        <div className="space-y-0.5">
          <Label>{t("settings.autoStopLoss")}</Label>
          <p className="text-xs text-muted-foreground">
            {t("settings.autoStopLossDesc")}
          </p>
        </div>
        <Switch checked={autoSlEnabled} onCheckedChange={setAutoSlEnabled} />
      </div>

      {/* Auto TP */}
      <div className="flex items-center justify-between max-w-[400px]">
        <div className="space-y-0.5">
          <Label>{t("settings.autoTakeProfit")}</Label>
          <p className="text-xs text-muted-foreground">
            {t("settings.autoTakeProfitDesc")}
          </p>
        </div>
        <Switch checked={autoTpEnabled} onCheckedChange={setAutoTpEnabled} />
      </div>

      {/* Message */}
      {message && (
        <p
          className={`text-sm ${
            message.type === "success" ? "text-emerald-500" : "text-red-500"
          }`}
        >
          {message.text}
        </p>
      )}

      {/* Save Button */}
      <Button onClick={handleSave} disabled={saving} className="w-full max-w-[200px]">
        {saving ? t("common.saving") : t("settings.saveTradeSettings")}
      </Button>
    </div>
  );
}
