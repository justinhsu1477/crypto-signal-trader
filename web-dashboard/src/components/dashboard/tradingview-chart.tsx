"use client";

import { useState, useEffect, useRef, useMemo } from "react";
import { Card, CardContent, CardHeader, CardAction } from "@/components/ui/card";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useT } from "@/lib/i18n/i18n-context";
import type { OpenPositionSummary } from "@/types";

const TV_LOCALE_MAP: Record<string, string> = {
  en: "en",
  "zh-TW": "zh_TW",
  "zh-CN": "zh_CN",
  ja: "ja",
};

const DEFAULT_SYMBOL = "BTCUSDT";

interface TradingViewChartProps {
  positions: OpenPositionSummary[];
}

function TradingViewWidget({ symbol, locale }: { symbol: string; locale: string }) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const script = document.createElement("script");
    script.src =
      "https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js";
    script.type = "text/javascript";
    script.async = true;
    script.textContent = JSON.stringify({
      autosize: true,
      symbol,
      interval: "60",
      timezone: "Asia/Taipei",
      theme: "dark",
      style: "1",
      locale,
      allow_symbol_change: false,
      support_host: "https://www.tradingview.com",
      studies: ["RSI@tv-basicstudies", "MAExp@tv-basicstudies"],
    });

    container.appendChild(script);

    return () => {
      while (container.firstChild) {
        container.removeChild(container.firstChild);
      }
    };
  }, [symbol, locale]);

  return (
    <div
      ref={containerRef}
      className="tradingview-widget-container"
      style={{ height: "calc(100vh - 10rem)", width: "100%" }}
    />
  );
}

export function TradingViewChart({ positions }: TradingViewChartProps) {
  const { locale } = useT();

  const uniqueSymbols = useMemo(() => {
    const seen = new Set<string>();
    return positions
      .map((p) => p.symbol)
      .filter((s) => {
        if (seen.has(s)) return false;
        seen.add(s);
        return true;
      });
  }, [positions]);

  const [selectedSymbol, setSelectedSymbol] = useState(
    uniqueSymbols[0] ?? DEFAULT_SYMBOL,
  );

  // Derive active symbol: fallback if selectedSymbol is no longer in the list
  const activeSymbol =
    uniqueSymbols.includes(selectedSymbol)
      ? selectedSymbol
      : uniqueSymbols[0] ?? DEFAULT_SYMBOL;

  const tvLocale = TV_LOCALE_MAP[locale] ?? "en";
  const tvSymbol = `BINANCE:${activeSymbol}`;
  const showTabs = uniqueSymbols.length > 1;

  return (
    <Card>
      {showTabs && (
        <CardHeader>
          <CardAction>
            <Tabs value={activeSymbol} onValueChange={setSelectedSymbol}>
              <TabsList>
                {uniqueSymbols.map((s) => (
                  <TabsTrigger key={s} value={s}>
                    {s.replace("USDT", "")}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
          </CardAction>
        </CardHeader>
      )}
      <CardContent>
        <TradingViewWidget
          key={activeSymbol}
          symbol={tvSymbol}
          locale={tvLocale}
        />
      </CardContent>
    </Card>
  );
}
