"use client";

import { useState } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { useAuth } from "@/lib/auth-context";
import {
  adminBroadcastTrade,
  type BroadcastTradeRequest,
  type BroadcastTradeResponse,
} from "@/lib/api";
import { toast } from "sonner";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  Loader2,
  Zap,
  CheckCircle2,
  XCircle,
  AlertTriangle,
} from "lucide-react";

const SYMBOLS = [
  "BTCUSDT",
  "ETHUSDT",
  "SOLUSDT",
  "BNBUSDT",
  "XRPUSDT",
  "DOGEUSDT",
  "ADAUSDT",
  "AVAXUSDT",
  "LINKUSDT",
  "SUIUSDT",
];

type Action = "CLOSE" | "ENTRY" | "MOVE_SL" | "CANCEL";

export default function AdminSignalPage() {
  const { t } = useT();
  const { email } = useAuth();

  // Form state
  const [action, setAction] = useState<Action>("CLOSE");
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [side, setSide] = useState<"LONG" | "SHORT">("LONG");
  const [entryPrice, setEntryPrice] = useState("");
  const [stopLoss, setStopLoss] = useState("");
  const [takeProfit, setTakeProfit] = useState("");
  const [closeRatio, setCloseRatio] = useState(100);
  const [newStopLoss, setNewStopLoss] = useState("");
  const [newTakeProfit, setNewTakeProfit] = useState("");
  const [isDca, setIsDca] = useState(false);

  // UI state
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<BroadcastTradeResponse | null>(null);
  const [error, setError] = useState("");

  function resetForm() {
    setEntryPrice("");
    setStopLoss("");
    setTakeProfit("");
    setCloseRatio(100);
    setNewStopLoss("");
    setNewTakeProfit("");
    setIsDca(false);
    setResult(null);
    setError("");
  }

  function isValid(): boolean {
    switch (action) {
      case "CLOSE":
        return !!symbol;
      case "ENTRY":
        return !!symbol && !!entryPrice && !!stopLoss;
      case "MOVE_SL":
        return !!symbol && !!newStopLoss;
      case "CANCEL":
        return !!symbol;
      default:
        return false;
    }
  }

  function buildRequest(): BroadcastTradeRequest {
    const req: BroadcastTradeRequest = {
      action,
      symbol,
      source: { platform: "ADMIN_DASHBOARD", author_name: email || "admin" },
    };

    switch (action) {
      case "CLOSE":
        req.close_ratio = closeRatio / 100;
        break;
      case "ENTRY":
        req.side = side;
        req.entry_price = parseFloat(entryPrice);
        req.stop_loss = parseFloat(stopLoss);
        if (takeProfit) req.take_profit = parseFloat(takeProfit);
        if (isDca) req.is_dca = true;
        break;
      case "MOVE_SL":
        req.new_stop_loss = parseFloat(newStopLoss);
        if (newTakeProfit) req.new_take_profit = parseFloat(newTakeProfit);
        break;
      case "CANCEL":
        break;
    }
    return req;
  }

  function getSummaryText(): string {
    switch (action) {
      case "CLOSE":
        return `CLOSE ${symbol} ${closeRatio}%`;
      case "ENTRY":
        return `ENTRY ${side} ${symbol} @ ${entryPrice} SL: ${stopLoss}${takeProfit ? ` TP: ${takeProfit}` : ""}${isDca ? " (DCA)" : ""}`;
      case "MOVE_SL":
        return `MOVE_SL ${symbol} → ${newStopLoss}${newTakeProfit ? ` TP: ${newTakeProfit}` : ""}`;
      case "CANCEL":
        return `CANCEL ${symbol}`;
      default:
        return "";
    }
  }

  async function handleBroadcast() {
    setSending(true);
    setError("");
    setResult(null);
    setConfirmOpen(false);

    try {
      const req = buildRequest();
      const res = await adminBroadcastTrade(req);
      setResult(res);

      if (res.status === "COMPLETED") {
        toast.success(t("adminSignal.success"));
      } else if (res.status === "SKIPPED") {
        toast.warning(res.reason || "Signal skipped");
      } else {
        toast.error(res.error || t("adminSignal.failed"));
      }
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : t("adminSignal.failed");
      setError(msg);
      toast.error(msg);
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold flex items-center gap-2">
          <Zap className="h-6 w-6 text-yellow-400" />
          {t("adminSignal.title")}
        </h1>
        <p className="text-sm text-muted-foreground mt-1">
          {t("adminSignal.description")}
        </p>
      </div>

      {/* Main Card */}
      <div className="rounded-xl border border-border bg-card p-6">
        {/* Action Tabs */}
        <Tabs
          value={action}
          onValueChange={(v) => {
            setAction(v as Action);
            resetForm();
          }}
        >
          <TabsList className="w-full grid grid-cols-4">
            <TabsTrigger value="CLOSE">CLOSE</TabsTrigger>
            <TabsTrigger value="ENTRY">ENTRY</TabsTrigger>
            <TabsTrigger value="MOVE_SL">MOVE SL</TabsTrigger>
            <TabsTrigger value="CANCEL">CANCEL</TabsTrigger>
          </TabsList>

          {/* Symbol Selector (共用) */}
          <div className="mt-6 space-y-2">
            <Label className="text-sm text-muted-foreground">
              {t("adminSignal.symbol")}
            </Label>
            <select
              value={symbol}
              onChange={(e) => setSymbol(e.target.value)}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {SYMBOLS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>

          <Separator className="my-6" />

          {/* CLOSE Fields */}
          <TabsContent value="CLOSE">
            <div className="space-y-4">
              <div className="space-y-2">
                <Label className="text-sm text-muted-foreground">
                  {t("adminSignal.closeRatio")}: {closeRatio}%
                </Label>
                <input
                  type="range"
                  min={10}
                  max={100}
                  step={10}
                  value={closeRatio}
                  onChange={(e) => setCloseRatio(Number(e.target.value))}
                  className="w-full accent-purple-500"
                />
                <div className="flex justify-between text-xs text-muted-foreground">
                  <span>10%</span>
                  <span>50%</span>
                  <span>100%</span>
                </div>
              </div>
            </div>
          </TabsContent>

          {/* ENTRY Fields */}
          <TabsContent value="ENTRY">
            <div className="space-y-4">
              {/* Side */}
              <div className="space-y-2">
                <Label className="text-sm text-muted-foreground">
                  {t("adminSignal.side")}
                </Label>
                <div className="flex gap-4">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="radio"
                      name="side"
                      checked={side === "LONG"}
                      onChange={() => setSide("LONG")}
                      className="accent-green-500"
                    />
                    <span className="text-sm font-medium text-green-400">
                      LONG
                    </span>
                  </label>
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="radio"
                      name="side"
                      checked={side === "SHORT"}
                      onChange={() => setSide("SHORT")}
                      className="accent-red-500"
                    />
                    <span className="text-sm font-medium text-red-400">
                      SHORT
                    </span>
                  </label>
                </div>
              </div>

              {/* Entry Price */}
              <div className="space-y-2">
                <Label className="text-sm text-muted-foreground">
                  {t("adminSignal.entryPrice")} *
                </Label>
                <Input
                  type="number"
                  step="any"
                  value={entryPrice}
                  onChange={(e) => setEntryPrice(e.target.value)}
                  placeholder="0.00"
                />
              </div>

              {/* Stop Loss */}
              <div className="space-y-2">
                <Label className="text-sm text-muted-foreground">
                  {t("adminSignal.stopLoss")} *
                </Label>
                <Input
                  type="number"
                  step="any"
                  value={stopLoss}
                  onChange={(e) => setStopLoss(e.target.value)}
                  placeholder="0.00"
                />
              </div>

              {/* Take Profit */}
              <div className="space-y-2">
                <Label className="text-sm text-muted-foreground">
                  {t("adminSignal.takeProfit")}
                </Label>
                <Input
                  type="number"
                  step="any"
                  value={takeProfit}
                  onChange={(e) => setTakeProfit(e.target.value)}
                  placeholder="0.00"
                />
              </div>

              {/* DCA */}
              <div className="flex items-center gap-3">
                <Switch
                  checked={isDca}
                  onCheckedChange={setIsDca}
                />
                <Label className="text-sm cursor-pointer">
                  {t("adminSignal.isDca")}
                </Label>
              </div>
            </div>
          </TabsContent>

          {/* MOVE_SL Fields */}
          <TabsContent value="MOVE_SL">
            <div className="space-y-4">
              <div className="space-y-2">
                <Label className="text-sm text-muted-foreground">
                  {t("adminSignal.newStopLoss")} *
                </Label>
                <Input
                  type="number"
                  step="any"
                  value={newStopLoss}
                  onChange={(e) => setNewStopLoss(e.target.value)}
                  placeholder="0.00"
                />
              </div>
              <div className="space-y-2">
                <Label className="text-sm text-muted-foreground">
                  {t("adminSignal.newTakeProfit")}
                </Label>
                <Input
                  type="number"
                  step="any"
                  value={newTakeProfit}
                  onChange={(e) => setNewTakeProfit(e.target.value)}
                  placeholder="0.00"
                />
              </div>
            </div>
          </TabsContent>

          {/* CANCEL Fields — symbol only, no extra fields */}
          <TabsContent value="CANCEL">
            <p className="text-sm text-muted-foreground">
              {t("adminSignal.cancelDesc")}
            </p>
          </TabsContent>
        </Tabs>

        {/* Broadcast Button */}
        <div className="mt-8">
          <Button
            size="lg"
            className="w-full bg-yellow-500 hover:bg-yellow-600 text-black font-bold"
            disabled={!isValid() || sending}
            onClick={() => setConfirmOpen(true)}
          >
            {sending ? (
              <>
                <Loader2 className="mr-2 h-5 w-5 animate-spin" />
                {t("adminSignal.sending")}
              </>
            ) : (
              <>
                <Zap className="mr-2 h-5 w-5" />
                {t("adminSignal.confirm")}
              </>
            )}
          </Button>
        </div>

        {/* Result */}
        {result && (
          <div className="mt-6">
            <Separator className="mb-4" />
            {result.status === "COMPLETED" ? (
              <div className="rounded-lg bg-green-500/10 border border-green-500/20 p-4 space-y-2">
                <div className="flex items-center gap-2 text-green-400 font-medium">
                  <CheckCircle2 className="h-5 w-5" />
                  {t("adminSignal.success")}
                </div>
                <p className="text-sm text-muted-foreground">
                  {t("adminSignal.resultUsers")
                    .replace("{success}", String(result.successCount ?? 0))
                    .replace("{fail}", String(result.failCount ?? 0))}
                </p>
                <p className="text-sm text-muted-foreground">
                  {t("adminSignal.skipped")
                    .replace(
                      "{noSub}",
                      String(result.skippedNoSubscription ?? 0)
                    )
                    .replace(
                      "{noKey}",
                      String(result.skippedNoApiKey ?? 0)
                    )}
                </p>
              </div>
            ) : result.status === "SKIPPED" ? (
              <div className="rounded-lg bg-yellow-500/10 border border-yellow-500/20 p-4">
                <div className="flex items-center gap-2 text-yellow-400 font-medium">
                  <AlertTriangle className="h-5 w-5" />
                  {t("adminSignal.skippedSignal")}
                </div>
                <p className="text-sm text-muted-foreground mt-1">
                  {result.reason || result.message}
                </p>
              </div>
            ) : (
              <div className="rounded-lg bg-red-500/10 border border-red-500/20 p-4">
                <div className="flex items-center gap-2 text-red-400 font-medium">
                  <XCircle className="h-5 w-5" />
                  {t("adminSignal.failed")}
                </div>
                <p className="text-sm text-muted-foreground mt-1">
                  {result.error || result.message}
                </p>
              </div>
            )}
          </div>
        )}

        {/* Error */}
        {error && !result && (
          <div className="mt-6">
            <Separator className="mb-4" />
            <div className="rounded-lg bg-red-500/10 border border-red-500/20 p-4">
              <div className="flex items-center gap-2 text-red-400 font-medium">
                <XCircle className="h-5 w-5" />
                {t("adminSignal.failed")}
              </div>
              <p className="text-sm text-muted-foreground mt-1">{error}</p>
            </div>
          </div>
        )}
      </div>

      {/* Confirm Dialog */}
      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("adminSignal.confirmTitle")}</DialogTitle>
            <DialogDescription>
              {t("adminSignal.confirmMessage")}
            </DialogDescription>
          </DialogHeader>

          <div className="rounded-lg bg-yellow-500/10 border border-yellow-500/20 p-4">
            <code className="text-sm font-mono text-yellow-300 whitespace-pre-wrap">
              {getSummaryText()}
            </code>
          </div>

          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setConfirmOpen(false)}
              disabled={sending}
            >
              {t("common.cancel")}
            </Button>
            <Button
              className="bg-yellow-500 hover:bg-yellow-600 text-black font-bold"
              onClick={handleBroadcast}
              disabled={sending}
            >
              {sending ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  {t("adminSignal.sending")}
                </>
              ) : (
                t("adminSignal.confirmBroadcast")
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
