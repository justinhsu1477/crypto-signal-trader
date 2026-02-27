"use client";

import { useState } from "react";
import Link from "next/link";
import { CheckCircle2, Circle, X, Rocket, Settings, Bell } from "lucide-react";
import { useT } from "@/lib/i18n/i18n-context";
import type { DashboardOverview } from "@/types";

const DISMISS_KEY = "onboarding-checklist-dismissed";

interface OnboardingChecklistProps {
  data: DashboardOverview;
}

interface CheckItem {
  key: string;
  done: boolean;
  required: boolean;
  href: string;
  icon: React.ReactNode;
}

/**
 * 新手引導 Checklist
 *
 * 顯示於 Dashboard 頂部，引導新用戶完成必要設定：
 * 1. 設定 Binance API Key（必要）
 * 2. 開啟自動跟單（必要）
 * 3. 設定 Discord Webhook（建議）
 *
 * 全部完成 or 用戶 dismiss → 不顯示
 * dismiss 記在 localStorage（持久化）
 */
export function OnboardingChecklist({ data }: OnboardingChecklistProps) {
  const { t } = useT();
  const [dismissed, setDismissed] = useState(() => {
    if (typeof window === "undefined") return false;
    return localStorage.getItem(DISMISS_KEY) === "true";
  });

  const items: CheckItem[] = [
    {
      key: "apiKey",
      done: data.hasBinanceApiKey,
      required: true,
      href: "/settings",
      icon: <Settings className="h-4 w-4" />,
    },
    {
      key: "autoTrade",
      done: data.autoTradeEnabled,
      required: true,
      href: "/settings",
      icon: <Rocket className="h-4 w-4" />,
    },
    {
      key: "webhook",
      done: data.hasDiscordWebhook,
      required: false,
      href: "/settings",
      icon: <Bell className="h-4 w-4" />,
    },
  ];

  // 必要項目全完成 → 自動隱藏
  const requiredDone = items.filter((i) => i.required).every((i) => i.done);
  const allDone = items.every((i) => i.done);
  const completedCount = items.filter((i) => i.done).length;

  if (dismissed || requiredDone) return null;

  function handleDismiss() {
    setDismissed(true);
    localStorage.setItem(DISMISS_KEY, "true");
  }

  return (
    <div className="rounded-xl border border-border bg-card p-5 shadow-sm">
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <Rocket className="h-5 w-5 text-primary" />
          <h3 className="font-semibold text-base">{t("onboarding.title")}</h3>
          <span className="text-xs text-muted-foreground">
            {completedCount}/{items.length}
          </span>
        </div>
        <button
          onClick={handleDismiss}
          className="p-1 rounded hover:bg-muted text-muted-foreground"
          aria-label="Dismiss"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      {/* Progress bar */}
      <div className="h-1.5 w-full rounded-full bg-muted mb-4">
        <div
          className="h-full rounded-full bg-primary transition-all duration-500"
          style={{ width: `${(completedCount / items.length) * 100}%` }}
        />
      </div>

      {/* Checklist items */}
      <div className="space-y-2">
        {items.map((item) => (
          <Link
            key={item.key}
            href={item.href}
            className={`flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-colors ${
              item.done
                ? "text-muted-foreground"
                : "hover:bg-muted/50 text-foreground"
            }`}
          >
            {item.done ? (
              <CheckCircle2 className="h-5 w-5 text-green-500 shrink-0" />
            ) : (
              <Circle className="h-5 w-5 text-muted-foreground shrink-0" />
            )}
            <span className="flex items-center gap-2">
              {item.icon}
              <span className={item.done ? "line-through" : ""}>
                {t(`onboarding.step_${item.key}`)}
              </span>
            </span>
            {!item.required && (
              <span className="ml-auto text-xs text-muted-foreground">
                {t("onboarding.optional")}
              </span>
            )}
          </Link>
        ))}
      </div>
    </div>
  );
}
