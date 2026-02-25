"use client";

import Link from "next/link";
import { Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useT } from "@/lib/i18n/i18n-context";

interface PricingTier {
  nameKey: string;
  price: string | null;
  priceLabel?: string;
  badgeKey?: string;
  featureKeys: string[];
  highlighted?: boolean;
  ctaKey: string;
}

export function PricingSection() {
  const { t } = useT();

  const tiers: PricingTier[] = [
    {
      nameKey: "landing.pricingStarter",
      price: null,
      priceLabel: "landing.pricingFree",
      featureKeys: [
        "landing.pricingStarterF1",
        "landing.pricingStarterF2",
        "landing.pricingStarterF3",
        "landing.pricingStarterF4",
        "landing.pricingStarterF5",
      ],
      ctaKey: "landing.pricingGetStarted",
    },
    {
      nameKey: "landing.pricingBasic",
      price: "19 USDT",
      featureKeys: [
        "landing.pricingBasicF1",
        "landing.pricingBasicF2",
        "landing.pricingBasicF3",
        "landing.pricingBasicF4",
        "landing.pricingBasicF5",
        "landing.pricingBasicF6",
      ],
      ctaKey: "landing.pricingSubscribe",
    },
    {
      nameKey: "landing.pricingPro",
      price: "49 USDT",
      badgeKey: "landing.pricingMostPopular",
      highlighted: true,
      featureKeys: [
        "landing.pricingProF1",
        "landing.pricingProF2",
        "landing.pricingProF3",
        "landing.pricingProF4",
        "landing.pricingProF5",
        "landing.pricingProF6",
      ],
      ctaKey: "landing.pricingSubscribe",
    },
  ];

  return (
    <section id="pricing" className="scroll-mt-nav relative z-10 py-24 px-6">
      <div className="max-w-5xl mx-auto">
        <div className="text-center mb-16">
          <div className="inline-flex items-center gap-2 rounded-full border border-emerald-500/20 bg-emerald-500/5 px-4 py-1.5 text-sm text-emerald-400 mb-4">
            {t("landing.pricingBadge")}
          </div>
          <h2 className="text-3xl sm:text-4xl font-bold tracking-tight">
            {t("landing.pricingTitle")}
          </h2>
          <p className="mt-4 text-lg text-muted-foreground max-w-2xl mx-auto">
            {t("landing.pricingSubtitle")}
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {tiers.map((tier) => (
            <div
              key={tier.nameKey}
              className={`relative rounded-2xl border p-6 flex flex-col ${
                tier.highlighted
                  ? "border-emerald-500/30 bg-emerald-500/5"
                  : "border-white/5 bg-white/[0.02]"
              }`}
            >
              {tier.badgeKey && (
                <div className="absolute -top-3 left-1/2 -translate-x-1/2">
                  <span className="rounded-full bg-emerald-500 px-3 py-1 text-xs font-medium text-white">
                    {t(tier.badgeKey)}
                  </span>
                </div>
              )}

              <div className="mb-6">
                <h3 className="text-lg font-semibold">{t(tier.nameKey)}</h3>
                <div className="mt-3 flex items-baseline gap-1">
                  {tier.price ? (
                    <>
                      <span className="text-4xl font-bold">{tier.price}</span>
                      <span className="text-muted-foreground text-sm">{t("landing.pricingPerMonth")}</span>
                    </>
                  ) : (
                    <span className="text-4xl font-bold">{t(tier.priceLabel!)}</span>
                  )}
                </div>
                {!tier.price && (
                  <p className="text-xs text-muted-foreground mt-1">{t("landing.pricingTrialDays")}</p>
                )}
              </div>

              <ul className="space-y-3 mb-8 flex-1">
                {tier.featureKeys.map((fk) => (
                  <li key={fk} className="flex items-start gap-2.5 text-sm">
                    <Check className="h-4 w-4 text-emerald-400 mt-0.5 shrink-0" />
                    <span className="text-muted-foreground">{t(fk)}</span>
                  </li>
                ))}
              </ul>

              <Button
                asChild
                className={`w-full ${
                  tier.highlighted
                    ? "bg-emerald-600 hover:bg-emerald-500 text-white"
                    : "bg-white/5 hover:bg-white/10 text-foreground"
                }`}
              >
                <Link href="/register">{t(tier.ctaKey)}</Link>
              </Button>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
