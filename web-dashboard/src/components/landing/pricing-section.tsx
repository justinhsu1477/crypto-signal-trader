"use client";

import { useRouter, usePathname } from "next/navigation";
import { Check } from "lucide-react";
import { useT } from "@/lib/i18n/i18n-context";
import { useScrollReveal } from "@/hooks/use-scroll-reveal";

/* ── Lido-style 3D isometric SVG illustrations ── */
function StarterIllustration() {
  return (
    <svg width="100" height="100" viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
      {/* Blue isometric cube */}
      <path d="M50 15L85 35V65L50 85L15 65V35L50 15Z" fill="url(#starterGrad)" />
      <path d="M50 15L85 35L50 55L15 35L50 15Z" fill="url(#starterTop)" />
      <path d="M50 55L85 35V65L50 85V55Z" fill="url(#starterRight)" />
      <path d="M50 55L15 35V65L50 85V55Z" fill="url(#starterLeft)" />
      {/* Lightning bolt */}
      <path d="M55 30L43 50H52L45 70L62 47H51L55 30Z" fill="white" opacity="0.9" />
      <defs>
        <linearGradient id="starterGrad" x1="15" y1="15" x2="85" y2="85">
          <stop stopColor="#6BB8FF" />
          <stop offset="1" stopColor="#4A90D9" />
        </linearGradient>
        <linearGradient id="starterTop" x1="50" y1="15" x2="50" y2="55">
          <stop stopColor="#A8D4FF" />
          <stop offset="1" stopColor="#7BB8F0" />
        </linearGradient>
        <linearGradient id="starterRight" x1="85" y1="35" x2="50" y2="85">
          <stop stopColor="#5A9FE0" />
          <stop offset="1" stopColor="#3A7BC8" />
        </linearGradient>
        <linearGradient id="starterLeft" x1="15" y1="35" x2="50" y2="85">
          <stop stopColor="#7BB8F0" />
          <stop offset="1" stopColor="#4A90D9" />
        </linearGradient>
      </defs>
    </svg>
  );
}

function BasicIllustration() {
  return (
    <svg width="100" height="100" viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
      {/* Yellow/gold isometric cube */}
      <path d="M50 18L82 36V64L50 82L18 64V36L50 18Z" fill="url(#basicGrad)" />
      <path d="M50 18L82 36L50 54L18 36L50 18Z" fill="url(#basicTop)" />
      <path d="M50 54L82 36V64L50 82V54Z" fill="url(#basicRight)" />
      <path d="M50 54L18 36V64L50 82V54Z" fill="url(#basicLeft)" />
      {/* Shield icon */}
      <path d="M50 30C50 30 38 36 38 44V52C38 58 43 64 50 67C57 64 62 58 62 52V44C62 36 50 30 50 30Z" fill="white" opacity="0.9" />
      <path d="M47 51L44 48L42.5 49.5L47 54L58 43L56.5 41.5L47 51Z" fill="#E8A800" />
      <defs>
        <linearGradient id="basicGrad" x1="18" y1="18" x2="82" y2="82">
          <stop stopColor="#FFD54F" />
          <stop offset="1" stopColor="#F0A800" />
        </linearGradient>
        <linearGradient id="basicTop" x1="50" y1="18" x2="50" y2="54">
          <stop stopColor="#FFE082" />
          <stop offset="1" stopColor="#FFD54F" />
        </linearGradient>
        <linearGradient id="basicRight" x1="82" y1="36" x2="50" y2="82">
          <stop stopColor="#E8A800" />
          <stop offset="1" stopColor="#C08800" />
        </linearGradient>
        <linearGradient id="basicLeft" x1="18" y1="36" x2="50" y2="82">
          <stop stopColor="#FFD54F" />
          <stop offset="1" stopColor="#E8A800" />
        </linearGradient>
      </defs>
    </svg>
  );
}

function ProIllustration() {
  return (
    <svg width="100" height="100" viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
      {/* Orange/coral isometric cube */}
      <path d="M50 18L82 36V64L50 82L18 64V36L50 18Z" fill="url(#proGrad)" />
      <path d="M50 18L82 36L50 54L18 36L50 18Z" fill="url(#proTop)" />
      <path d="M50 54L82 36V64L50 82V54Z" fill="url(#proRight)" />
      <path d="M50 54L18 36V64L50 82V54Z" fill="url(#proLeft)" />
      {/* Chart/rocket icon */}
      <path d="M36 60L44 48L52 54L64 38" stroke="white" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" opacity="0.9" />
      <path d="M58 38H64V44" stroke="white" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" opacity="0.9" />
      {/* Glow ring */}
      <circle cx="50" cy="22" r="8" fill="url(#proGlow)" opacity="0.4" />
      <defs>
        <linearGradient id="proGrad" x1="18" y1="18" x2="82" y2="82">
          <stop stopColor="#FF8A65" />
          <stop offset="1" stopColor="#E65100" />
        </linearGradient>
        <linearGradient id="proTop" x1="50" y1="18" x2="50" y2="54">
          <stop stopColor="#FFAB91" />
          <stop offset="1" stopColor="#FF8A65" />
        </linearGradient>
        <linearGradient id="proRight" x1="82" y1="36" x2="50" y2="82">
          <stop stopColor="#E65100" />
          <stop offset="1" stopColor="#BF360C" />
        </linearGradient>
        <linearGradient id="proLeft" x1="18" y1="36" x2="50" y2="82">
          <stop stopColor="#FF8A65" />
          <stop offset="1" stopColor="#E65100" />
        </linearGradient>
        <radialGradient id="proGlow" cx="50" cy="22" r="8">
          <stop stopColor="#4DD0E1" />
          <stop offset="1" stopColor="#4DD0E1" stopOpacity="0" />
        </radialGradient>
      </defs>
    </svg>
  );
}

const illustrations = [StarterIllustration, BasicIllustration, ProIllustration];

/**
 * Lido-style pricing section: 3 white rounded cards (30px radius) on warm bg.
 * Uses 3D isometric SVG illustrations like lido.fi — no box framing.
 */
export function PricingSection() {
  const router = useRouter();
  const pathname = usePathname();
  const { t } = useT();
  const ref = useScrollReveal();

  function handleCta() {
    window.scrollTo({ top: 0, behavior: "smooth" });
    if (pathname !== "/register") {
      router.push("/register");
    }
  }

  const tiers = [
    {
      nameKey: "landing.pricingStarter",
      price: null,
      priceLabel: "landing.pricingFree",
      featureKeys: [
        "landing.pricingStarterF1",
        "landing.pricingStarterF2",
        "landing.pricingStarterF3",
      ],
      ctaKey: "landing.pricingGetStarted",
    },
    {
      nameKey: "landing.pricingBasic",
      price: "99 USDT",
      featureKeys: [
        "landing.pricingBasicF1",
        "landing.pricingBasicF2",
        "landing.pricingBasicF3",
      ],
      ctaKey: "landing.pricingSubscribe",
    },
    {
      nameKey: "landing.pricingPro",
      price: "199 USDT",
      featureKeys: [
        "landing.pricingProF1",
        "landing.pricingProF2",
        "landing.pricingProF3",
      ],
      ctaKey: "landing.pricingSubscribe",
    },
  ];

  return (
    <section id="pricing" className="scroll-mt-nav relative z-10 px-6 py-24 md:px-10">
      <div className="mx-auto max-w-[1400px]">
        <div className="mb-14 text-center">
          <h2
            className="text-3xl font-normal tracking-tight text-black sm:text-4xl lg:text-5xl"
            style={{ letterSpacing: "-0.01em" }}
          >
            {t("landing.pricingTitle")}
          </h2>
          <p className="mx-auto mt-3 max-w-lg text-base text-gray-500">
            {t("landing.pricingSubtitle")}
          </p>
        </div>

        <div ref={ref} className="scroll-reveal grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {tiers.map((tier, idx) => {
            const Illustration = illustrations[idx];
            return (
              <div
                key={tier.nameKey}
                className="stagger-child relative flex flex-col rounded-[30px] bg-white p-8 shadow-[0_1px_3px_rgba(0,0,0,0.05)] transition-all duration-300 hover:shadow-[0_4px_12px_rgba(0,0,0,0.08)]"
              >
                {/* Illustration — large, no box */}
                <div className="mb-6">
                  <Illustration />
                </div>

                {/* Name + price */}
                <h3 className="text-xl font-bold text-black" style={{ letterSpacing: "-0.01em" }}>
                  {t(tier.nameKey)}
                </h3>
                <div className="mt-3 flex items-baseline gap-1">
                  {tier.price ? (
                    <>
                      <span className="text-3xl font-bold text-black">{tier.price}</span>
                      <span className="text-sm text-gray-400">{t("landing.pricingPerMonth")}</span>
                    </>
                  ) : (
                    <span className="text-3xl font-bold text-black">{t(tier.priceLabel!)}</span>
                  )}
                </div>
                {!tier.price && (
                  <p className="mt-1 text-xs text-gray-400">{t("landing.pricingTrialDays")}</p>
                )}

                {/* Features */}
                <ul className="mb-8 mt-6 flex-1 space-y-3">
                  {tier.featureKeys.map((fk) => (
                    <li key={fk} className="flex items-start gap-2.5 text-sm">
                      <Check className="mt-0.5 h-4 w-4 shrink-0 text-[#00d4aa]" />
                      <span className="text-gray-600">{t(fk)}</span>
                    </li>
                  ))}
                </ul>

                {/* CTA */}
                <button
                  onClick={handleCta}
                  className="w-full rounded-full bg-black py-3.5 text-sm font-bold text-white transition-colors hover:bg-gray-800"
                >
                  {t(tier.ctaKey)}
                </button>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
