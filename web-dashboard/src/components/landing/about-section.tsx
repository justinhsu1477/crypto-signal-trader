"use client";

import { useT } from "@/lib/i18n/i18n-context";

export function AboutSection() {
  const { t } = useT();

  const stats = [
    { value: "24/7", labelKey: "landing.aboutStat247" },
    { value: "AES-256", labelKey: "landing.aboutStatEncryption" },
    { value: "20+", labelKey: "landing.aboutStatMetrics" },
    { value: "<1s", labelKey: "landing.aboutStatSpeed" },
  ];

  return (
    <section id="about" className="scroll-mt-nav relative z-10 py-24 px-6">
      <div className="max-w-6xl mx-auto">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-12 items-center">
          <div>
            <div className="inline-flex items-center gap-2 rounded-full border border-emerald-500/20 bg-emerald-500/5 px-4 py-1.5 text-sm text-emerald-400 mb-4">
              {t("landing.aboutBadge")}
            </div>
            <h2 className="text-3xl sm:text-4xl font-bold tracking-tight">
              {t("landing.aboutTitle")}
            </h2>
            <p className="mt-6 text-muted-foreground leading-relaxed">
              {t("landing.aboutP1")}
            </p>
            <p className="mt-4 text-muted-foreground leading-relaxed">
              {t("landing.aboutP2")}
            </p>
          </div>

          <div className="grid grid-cols-2 gap-4">
            {stats.map((s) => (
              <div
                key={s.labelKey}
                className="rounded-2xl border border-white/5 bg-white/[0.02] p-6 text-center"
              >
                <div className="text-3xl font-bold bg-gradient-to-r from-emerald-400 to-blue-400 bg-clip-text text-transparent">
                  {s.value}
                </div>
                <p className="mt-2 text-sm text-muted-foreground">{t(s.labelKey)}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
