"use client";

import { Clock, Link2, Lock, Shield } from "lucide-react";
import { useT } from "@/lib/i18n/i18n-context";

const trustBadges = [
  { icon: Lock, labelKey: "landing.aboutTrustNonCustodial" },
  { icon: Shield, labelKey: "landing.aboutTrustEncrypted" },
  { icon: Link2, labelKey: "landing.aboutTrustBinance" },
  { icon: Clock, labelKey: "landing.aboutTrustUptime" },
];

export function AboutSection() {
  const { t } = useT();

  return (
    <section id="about" className="scroll-mt-nav relative z-10 px-6 py-20">
      <div className="mx-auto max-w-5xl">
        <div className="grid items-start gap-8 lg:grid-cols-[1.2fr_1fr]">
          <div>
            <div className="mb-4 inline-flex items-center gap-2 rounded-full border border-cyan-400/30 bg-cyan-400/10 px-4 py-1.5 text-sm text-cyan-200">
              {t("landing.aboutBadge")}
            </div>
            <h2 className="text-3xl font-semibold tracking-tight text-white sm:text-4xl">
              {t("landing.aboutTitle")}
            </h2>
            <p className="mt-5 text-base leading-relaxed text-slate-300">
              {t("landing.aboutP1")}
            </p>
            <details className="group mt-4 rounded-2xl border border-white/10 bg-white/[0.02] p-4 text-sm text-slate-300">
              <summary className="cursor-pointer list-none font-medium text-cyan-200 group-open:mb-3">
                {t("landing.aboutReadMore")}
              </summary>
              <p className="leading-relaxed">
                {t("landing.aboutLongP1")}
              </p>
              <p className="mt-3 leading-relaxed">
                {t("landing.aboutLongP2")}
              </p>
            </details>
          </div>

          <div className="grid grid-cols-2 gap-3">
            {trustBadges.map((b) => (
              <div
                key={b.labelKey}
                className="rounded-2xl border border-white/10 bg-white/[0.03] p-4"
              >
                <div className="mb-3 flex h-9 w-9 items-center justify-center rounded-lg bg-cyan-400/12">
                  <b.icon className="h-4 w-4 text-cyan-300" />
                </div>
                <p className="text-sm font-medium text-slate-100">{t(b.labelKey)}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
