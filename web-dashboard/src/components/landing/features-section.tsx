"use client";

import { Zap, Shield, TrendingUp, BarChart3, Lock, Bell } from "lucide-react";
import { useT } from "@/lib/i18n/i18n-context";

export function FeaturesSection() {
  const { t } = useT();

  const features = [
    { icon: Zap, titleKey: "landing.featureAutoExecTitle", descKey: "landing.featureAutoExecDesc" },
    { icon: Shield, titleKey: "landing.featureRiskMgmtTitle", descKey: "landing.featureRiskMgmtDesc" },
    { icon: TrendingUp, titleKey: "landing.featureDcaTitle", descKey: "landing.featureDcaDesc" },
    { icon: BarChart3, titleKey: "landing.featureAnalyticsTitle", descKey: "landing.featureAnalyticsDesc" },
    { icon: Lock, titleKey: "landing.featureNonCustodialTitle", descKey: "landing.featureNonCustodialDesc" },
    { icon: Bell, titleKey: "landing.featureNotificationsTitle", descKey: "landing.featureNotificationsDesc" },
  ];

  return (
    <section id="features" className="scroll-mt-nav relative z-10 py-24 px-6">
      <div className="max-w-6xl mx-auto">
        <div className="text-center mb-16">
          <div className="inline-flex items-center gap-2 rounded-full border border-emerald-500/20 bg-emerald-500/5 px-4 py-1.5 text-sm text-emerald-400 mb-4">
            {t("landing.featuresBadge")}
          </div>
          <h2 className="text-3xl sm:text-4xl font-bold tracking-tight">
            {t("landing.featuresTitle")}
          </h2>
          <p className="mt-4 text-lg text-muted-foreground max-w-2xl mx-auto">
            {t("landing.featuresSubtitle")}
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {features.map((f) => (
            <div
              key={f.titleKey}
              className="group rounded-2xl border border-white/5 bg-white/[0.02] p-6 hover:bg-white/[0.04] hover:border-emerald-500/20 transition-all duration-300"
            >
              <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-emerald-500/10 mb-4 group-hover:bg-emerald-500/15 transition-colors">
                <f.icon className="h-6 w-6 text-emerald-400" />
              </div>
              <h3 className="text-lg font-semibold mb-2">{t(f.titleKey)}</h3>
              <p className="text-sm text-muted-foreground leading-relaxed">{t(f.descKey)}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
