"use client";

import { useT } from "@/lib/i18n/i18n-context";

/**
 * Inline hero stats — matches lido.fi style (integrated into hero, not a separate section).
 * This component is now rendered inside AuthLayout's hero area.
 */
const stats = [
  { value: "24/7", labelKey: "landing.statsBarTrades" },
  { value: "<1s", labelKey: "landing.statsBarSpeed" },
  { value: "AES-256", labelKey: "landing.statsBarWinRate" },
];

export function StatsBarSection() {
  const { t } = useT();

  return (
    <div className="flex items-center gap-8">
      {stats.map((s, i) => (
        <div key={s.labelKey} className="flex items-center gap-8">
          {i > 0 && <div className="h-10 w-px bg-gray-200" />}
          <div>
            <div className="text-2xl font-bold tracking-tight text-black sm:text-3xl" style={{ letterSpacing: "-0.02em" }}>
              {s.value}
            </div>
            <p className="mt-0.5 text-xs text-gray-400">
              {t(s.labelKey)}
            </p>
          </div>
        </div>
      ))}
    </div>
  );
}
