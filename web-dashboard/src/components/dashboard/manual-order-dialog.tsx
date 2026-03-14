"use client";

import { useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { toast } from "sonner";

interface ManualOrderDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function ManualOrderDialog({ open, onOpenChange }: ManualOrderDialogProps) {
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [side, setSide] = useState<"LONG" | "SHORT">("LONG");
  const [entryPrice, setEntryPrice] = useState("");
  const [stopLoss, setStopLoss] = useState("");
  const [takeProfit, setTakeProfit] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit() {
    if (!symbol || !entryPrice || !stopLoss) {
      toast.error("Symbol, Entry Price, and Stop Loss are required");
      return;
    }

    const ep = parseFloat(entryPrice);
    const sl = parseFloat(stopLoss);
    const tp = takeProfit ? parseFloat(takeProfit) : undefined;

    if (isNaN(ep) || isNaN(sl)) {
      toast.error("Invalid price values");
      return;
    }

    // Safety check: SL direction
    if (side === "LONG" && sl >= ep) {
      toast.error("LONG: Stop Loss must be below Entry Price");
      return;
    }
    if (side === "SHORT" && sl <= ep) {
      toast.error("SHORT: Stop Loss must be above Entry Price");
      return;
    }

    setSubmitting(true);
    try {
      const res = await fetch("/api/execute-trade", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({
          action: "ENTRY",
          symbol: symbol.toUpperCase(),
          side,
          entry_price: ep,
          stop_loss: sl,
          take_profit: tp,
        }),
      });

      const data = await res.json();

      if (res.ok) {
        toast.success(`Order placed: ${symbol} ${side}`);
        onOpenChange(false);
        // Reset form
        setEntryPrice("");
        setStopLoss("");
        setTakeProfit("");
      } else {
        toast.error(data.error || "Order failed");
      }
    } catch {
      toast.error("Failed to place order");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Manual Order</DialogTitle>
          <DialogDescription>
            Place a manual trade order. This will execute on your Binance account.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {/* Symbol */}
          <div>
            <Label>Symbol</Label>
            <Input
              value={symbol}
              onChange={(e) => setSymbol(e.target.value.toUpperCase())}
              placeholder="BTCUSDT"
            />
          </div>

          {/* Side */}
          <div>
            <Label>Direction</Label>
            <div className="flex gap-2 mt-1">
              <button
                onClick={() => setSide("LONG")}
                className={`flex-1 py-2 rounded-md text-sm font-medium transition-colors ${
                  side === "LONG"
                    ? "bg-green-500/20 text-green-400 border border-green-500/30"
                    : "bg-muted text-muted-foreground hover:bg-accent"
                }`}
              >
                LONG
              </button>
              <button
                onClick={() => setSide("SHORT")}
                className={`flex-1 py-2 rounded-md text-sm font-medium transition-colors ${
                  side === "SHORT"
                    ? "bg-red-500/20 text-red-400 border border-red-500/30"
                    : "bg-muted text-muted-foreground hover:bg-accent"
                }`}
              >
                SHORT
              </button>
            </div>
          </div>

          {/* Entry Price */}
          <div>
            <Label>Entry Price (USDT)</Label>
            <Input
              type="number"
              step="any"
              value={entryPrice}
              onChange={(e) => setEntryPrice(e.target.value)}
              placeholder="95000"
            />
          </div>

          {/* Stop Loss */}
          <div>
            <Label>Stop Loss (USDT)</Label>
            <Input
              type="number"
              step="any"
              value={stopLoss}
              onChange={(e) => setStopLoss(e.target.value)}
              placeholder="94000"
            />
          </div>

          {/* Take Profit */}
          <div>
            <Label>Take Profit (USDT, optional)</Label>
            <Input
              type="number"
              step="any"
              value={takeProfit}
              onChange={(e) => setTakeProfit(e.target.value)}
              placeholder="98000"
            />
          </div>

          {/* Submit */}
          <Button
            className="w-full"
            onClick={handleSubmit}
            disabled={submitting}
          >
            {submitting ? "Placing Order..." : `Place ${side} Order`}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
