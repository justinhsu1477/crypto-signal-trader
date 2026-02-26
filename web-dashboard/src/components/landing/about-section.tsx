"use client";

import { Lock, Shield, Link2, Clock } from "lucide-react";
import { useT } from "@/lib/i18n/i18n-context";

const trustBadges = [
  { icon: Lock, labelKey: "landing.aboutTrustNonCustodial" },
  { icon: Shield, labelKey: "landing.aboutTrustEncrypted" },
  { icon: Link2, labelKey: "landing.aboutTrustBinance" },
  { icon: Clock, labelKey: "landing.aboutTrustUptime" },
];

const testimonials = [
  {
    quoteKey: "landing.aboutTestimonial1",
    nameKey: "landing.aboutTestimonialName1",
    roleKey: "landing.aboutTestimonialRole1",
  },
  {
    quoteKey: "landing.aboutTestimonial2",
    nameKey: "landing.aboutTestimonialName2",
    roleKey: "landing.aboutTestimonialRole2",
  },
];

export function AboutSection() {
  const { t } = useT();

  return (
    <section id="about" className="scroll-mt-nav relative z-10 py-24 px-6">
      <div className="max-w-6xl mx-auto">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-12 items-start">
          {/* Left column — text */}
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

          {/* Right column — trust badges + testimonials */}
          <div className="space-y-4">
            {/* Trust badges 2x2 */}
            <div className="grid grid-cols-2 gap-3">
              {trustBadges.map((b) => (
                <div
                  key={b.labelKey}
                  className="flex items-center gap-3 rounded-2xl border border-white/5 bg-white/[0.02] p-4"
                >
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-emerald-500/10">
                    <b.icon className="h-5 w-5 text-emerald-400" />
                  </div>
                  <span className="text-sm font-medium">{t(b.labelKey)}</span>
                </div>
              ))}
            </div>

            {/* Testimonials */}
            <div className="space-y-3">
              {testimonials.map((tm) => (
                <div
                  key={tm.nameKey}
                  className="rounded-2xl border border-white/5 bg-white/[0.02] p-5"
                >
                  <p className="text-sm text-muted-foreground leading-relaxed italic">
                    &ldquo;{t(tm.quoteKey)}&rdquo;
                  </p>
                  <div className="mt-3 flex items-center gap-3">
                    <div className="flex h-8 w-8 items-center justify-center rounded-full bg-emerald-500/10 text-xs font-bold text-emerald-400">
                      {t(tm.nameKey).charAt(0)}
                    </div>
                    <div>
                      <p className="text-sm font-medium">{t(tm.nameKey)}</p>
                      <p className="text-xs text-muted-foreground">{t(tm.roleKey)}</p>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
