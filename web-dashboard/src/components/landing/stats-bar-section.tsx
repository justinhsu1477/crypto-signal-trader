"use client";

import { useRef, useState, useEffect } from "react";
import { TrendingUp, Target, Users, Zap } from "lucide-react";
import { useT } from "@/lib/i18n/i18n-context";

function useCountUp(
  ref: React.RefObject<HTMLElement | null>,
  end: number,
  duration: number = 1500,
  suffix: string = "",
  prefix: string = "",
): string {
  const [display, setDisplay] = useState(`${prefix}0${suffix}`);
  const hasAnimated = useRef(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && !hasAnimated.current) {
          hasAnimated.current = true;
          const startTime = performance.now();

          function animate(now: number) {
            const elapsed = now - startTime;
            const progress = Math.min(elapsed / duration, 1);
            const eased = 1 - Math.pow(1 - progress, 3); // ease-out cubic
            const current = Math.round(eased * end);
            setDisplay(`${prefix}${current.toLocaleString()}${suffix}`);
            if (progress < 1) {
              requestAnimationFrame(animate);
            }
          }
          requestAnimationFrame(animate);
        }
      },
      { threshold: 0.3 },
    );

    observer.observe(el);
    return () => observer.disconnect();
  }, [ref, end, duration, suffix, prefix]);

  return display;
}

export function StatsBarSection() {
  const { t } = useT();
  const ref1 = useRef<HTMLDivElement>(null);
  const ref2 = useRef<HTMLDivElement>(null);
  const ref3 = useRef<HTMLDivElement>(null);
  const ref4 = useRef<HTMLDivElement>(null);

  const trades = useCountUp(ref1, 1000, 1800, "+");
  const winRate = useCountUp(ref2, 72, 1500, "%");
  const traders = useCountUp(ref3, 50, 1500, "+");
  const speed = useCountUp(ref4, 1, 800, "s", "<");

  const stats = [
    { ref: ref1, icon: TrendingUp, value: trades, labelKey: "landing.statsBarTrades" },
    { ref: ref2, icon: Target, value: winRate, labelKey: "landing.statsBarWinRate" },
    { ref: ref3, icon: Users, value: traders, labelKey: "landing.statsBarTraders" },
    { ref: ref4, icon: Zap, value: speed, labelKey: "landing.statsBarSpeed" },
  ];

  return (
    <section className="relative z-10 py-12 px-6">
      <div className="max-w-5xl mx-auto">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 md:gap-6">
          {stats.map((s) => (
            <div
              key={s.labelKey}
              ref={s.ref}
              className="flex flex-col items-center gap-2 rounded-2xl border border-white/5 bg-white/[0.02] p-6 text-center"
            >
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-500/10">
                <s.icon className="h-5 w-5 text-emerald-400" />
              </div>
              <div className="text-2xl sm:text-3xl font-bold bg-gradient-to-r from-emerald-400 to-blue-400 bg-clip-text text-transparent">
                {s.value}
              </div>
              <p className="text-sm text-muted-foreground">{t(s.labelKey)}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
