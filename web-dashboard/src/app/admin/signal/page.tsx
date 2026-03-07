"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { useAuth } from "@/lib/auth-context";
import {
  adminBroadcastTrade,
  getAdminSystemOverview,
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
  ChevronDown,
  ChevronRight,
  Search,
  UserCircle,
  CheckSquare,
  Square,
} from "lucide-react";
import type { UserTradingSummary } from "@/types";

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

  // Target user state
  const [targetEnabled, setTargetEnabled] = useState(false);
  const [users, setUsers] = useState<UserTradingSummary[]>([]);
  const [usersLoading, setUsersLoading] = useState(false);
  const [selectedUserIds, setSelectedUserIds] = useState<Set<string>>(new Set());
  const [userSearch, setUserSearch] = useState("");

  // UI state
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<BroadcastTradeResponse | null>(null);
  const [error, setError] = useState("");

  // Load users when target mode is enabled
  useEffect(() => {
    if (targetEnabled && users.length === 0 && !usersLoading) {
      setUsersLoading(true);
      getAdminSystemOverview()
        .then((overview) => setUsers(overview.userSummaries))
        .catch(() => {})
        .finally(() => setUsersLoading(false));
    }
  }, [targetEnabled, users.length, usersLoading]);

  const filteredUsers = useMemo(() => {
    if (!userSearch.trim()) return users;
    const q = userSearch.toLowerCase();
    return users.filter(
      (u) =>
        (u.name && u.name.toLowerCase().includes(q)) ||
        (u.email && u.email.toLowerCase().includes(q))
    );
  }, [users, userSearch]);

  const toggleUser = useCallback((userId: string) => {
    setSelectedUserIds((prev) => {
      const next = new Set(prev);
      if (next.has(userId)) next.delete(userId);
      else next.add(userId);
      return next;
    });
  }, []);

  function resetForm() {
    setEntryPrice("");
    setStopLoss("");
    setTakeProfit("");
    setCloseRatio(100);
    setNewStopLoss("");
    setNewTakeProfit("");
    setIsDca(false);
    setTargetEnabled(false);
    setSelectedUserIds(new Set());
    setUserSearch("");
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
    if (targetEnabled && selectedUserIds.size > 0) {
      req.target_user_ids = Array.from(selectedUserIds);
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
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold flex items-center gap-2">
            <Zap className="h-6 w-6 text-yellow-400" />
            {t("adminSignal.title")}
          </h1>
          <p className="text-sm text-muted-foreground mt-1">
            {t("adminSignal.description")}
          </p>
        </div>
        <Button
          className="bg-yellow-500 hover:bg-yellow-600 text-black font-bold shrink-0"
          disabled={!isValid() || sending}
          onClick={() => setConfirmOpen(true)}
        >
          {sending ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              {t("adminSignal.sending")}
            </>
          ) : (
            <>
              <Zap className="mr-2 h-4 w-4" />
              {t("adminSignal.confirm")}
            </>
          )}
        </Button>
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
                  {t("adminSignal.closeRatio")}
                </Label>
                <div className="flex items-center gap-3">
                  <Input
                    type="number"
                    min={1}
                    max={100}
                    step={1}
                    value={closeRatio}
                    onChange={(e) => {
                      const v = Number(e.target.value);
                      if (v >= 1 && v <= 100) setCloseRatio(v);
                    }}
                    className="w-24"
                  />
                  <span className="text-sm text-muted-foreground">%</span>
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

        {/* Target Users — collapsible */}
        <Separator className="my-6" />
        <div>
          <button
            type="button"
            onClick={() => setTargetEnabled((v) => !v)}
            className="flex items-center gap-2 text-sm font-medium hover:text-primary transition-colors w-full text-left"
          >
            {targetEnabled ? (
              <ChevronDown className="h-4 w-4" />
            ) : (
              <ChevronRight className="h-4 w-4" />
            )}
            {t("adminSignal.targetUsers")}
            {targetEnabled && selectedUserIds.size > 0 && (
              <span className="ml-auto text-xs text-yellow-400 font-normal">
                {t("adminSignal.targetUsersEnabled").replace(
                  "{count}",
                  String(selectedUserIds.size)
                )}
              </span>
            )}
            {!targetEnabled && (
              <span className="ml-auto text-xs text-muted-foreground font-normal">
                {t("adminSignal.allUsers")}
              </span>
            )}
          </button>
          <p className="text-xs text-muted-foreground mt-1 ml-6">
            {t("adminSignal.targetUsersDesc")}
          </p>

          {targetEnabled && (
            <div className="mt-3 rounded-lg border border-border overflow-hidden">
              {/* Search */}
              <div className="flex items-center gap-2 border-b border-border px-3 py-2">
                <Search className="h-4 w-4 text-muted-foreground shrink-0" />
                <input
                  type="text"
                  value={userSearch}
                  onChange={(e) => setUserSearch(e.target.value)}
                  placeholder={t("adminSignal.searchUsers")}
                  className="w-full bg-transparent text-sm focus:outline-none"
                />
                {selectedUserIds.size > 0 && (
                  <span className="text-xs text-muted-foreground whitespace-nowrap">
                    {t("adminSignal.selectedCount").replace(
                      "{count}",
                      String(selectedUserIds.size)
                    )}
                  </span>
                )}
              </div>

              {/* User list */}
              <div className="overflow-y-auto max-h-[250px]">
                {usersLoading ? (
                  <div className="flex items-center justify-center py-6">
                    <div className="animate-spin rounded-full h-5 w-5 border-b-2 border-primary" />
                  </div>
                ) : filteredUsers.length === 0 ? (
                  <div className="py-4 text-center text-sm text-muted-foreground">
                    {t("common.noData")}
                  </div>
                ) : (
                  filteredUsers.map((user) => {
                    const selected = selectedUserIds.has(user.userId);
                    return (
                      <button
                        key={user.userId}
                        type="button"
                        onClick={() => toggleUser(user.userId)}
                        className={`w-full flex items-center gap-3 px-3 py-2 text-left hover:bg-accent/50 transition-colors ${
                          selected ? "bg-accent/30" : ""
                        }`}
                      >
                        {selected ? (
                          <CheckSquare className="h-4 w-4 text-primary shrink-0" />
                        ) : (
                          <Square className="h-4 w-4 text-muted-foreground shrink-0" />
                        )}
                        <UserCircle className="h-5 w-5 text-muted-foreground shrink-0" />
                        <div className="flex-1 min-w-0">
                          <div className="text-sm font-medium truncate">
                            {user.name || "unknown"}
                          </div>
                          <div className="text-xs text-muted-foreground truncate">
                            {user.email || "LINE"}
                          </div>
                        </div>
                      </button>
                    );
                  })
                )}
              </div>
            </div>
          )}
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
                {(result.skippedNotTargeted ?? 0) > 0 && (
                  <p className="text-sm text-muted-foreground">
                    {t("adminSignal.skippedNotTargeted").replace(
                      "{count}",
                      String(result.skippedNotTargeted)
                    )}
                  </p>
                )}
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

          <div className="rounded-lg bg-yellow-500/10 border border-yellow-500/20 p-4 space-y-2">
            <code className="text-sm font-mono text-yellow-300 whitespace-pre-wrap">
              {getSummaryText()}
            </code>
            {targetEnabled && selectedUserIds.size > 0 && (
              <p className="text-sm text-yellow-400 font-medium">
                {t("adminSignal.confirmTargeted").replace(
                  "{count}",
                  String(selectedUserIds.size)
                )}
              </p>
            )}
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
