"use client";

import { useT } from "@/lib/i18n/i18n-context";
import { useScrollReveal } from "@/hooks/use-scroll-reveal";

/* ── Security shield SVG illustration ── */
function SecurityIllustration() {
  return (
    <svg width="240" height="240" viewBox="0 0 240 240" fill="none" xmlns="http://www.w3.org/2000/svg" className="w-full max-w-[240px] h-auto">
      {/* Outer glow circle */}
      <circle cx="120" cy="120" r="110" fill="url(#secGlow)" opacity="0.3" />
      {/* Shield body */}
      <path d="M120 40C120 40 70 65 70 100V150C70 175 90 200 120 215C150 200 170 175 170 150V100C170 65 120 40 120 40Z"
        fill="url(#shieldGrad)" />
      {/* Shield highlight */}
      <path d="M120 50C120 50 78 72 78 102V148C78 170 95 192 120 205C145 192 162 170 162 148V102C162 72 120 50 120 50Z"
        fill="url(#shieldInner)" opacity="0.6" />
      {/* Lock icon */}
      <rect x="105" y="115" width="30" height="25" rx="4" fill="white" opacity="0.95" />
      <path d="M112 115V108C112 103.6 115.6 100 120 100C124.4 100 128 103.6 128 108V115"
        stroke="white" strokeWidth="3.5" strokeLinecap="round" fill="none" opacity="0.95" />
      <circle cx="120" cy="126" r="3" fill="url(#lockDot)" />
      {/* Orbiting dots */}
      <circle cx="55" cy="90" r="6" fill="#A78BFA" opacity="0.5" />
      <circle cx="185" cy="90" r="4" fill="#6BB8FF" opacity="0.5" />
      <circle cx="175" cy="185" r="5" fill="#00d4aa" opacity="0.5" />
      <circle cx="65" cy="180" r="3.5" fill="#FFD54F" opacity="0.5" />
      <defs>
        <radialGradient id="secGlow" cx="120" cy="120" r="110">
          <stop stopColor="#C4B5FD" />
          <stop offset="1" stopColor="#C4B5FD" stopOpacity="0" />
        </radialGradient>
        <linearGradient id="shieldGrad" x1="70" y1="40" x2="170" y2="215">
          <stop stopColor="#8B5CF6" />
          <stop offset="0.5" stopColor="#6D28D9" />
          <stop offset="1" stopColor="#4C1D95" />
        </linearGradient>
        <linearGradient id="shieldInner" x1="120" y1="50" x2="120" y2="205">
          <stop stopColor="#A78BFA" />
          <stop offset="1" stopColor="#7C3AED" />
        </linearGradient>
        <radialGradient id="lockDot" cx="120" cy="126" r="3">
          <stop stopColor="#6D28D9" />
          <stop offset="1" stopColor="#4C1D95" />
        </radialGradient>
      </defs>
    </svg>
  );
}

/**
 * Lido-style security section with huge typography + trust card + right-side illustration.
 */
export function HowItWorksSection() {
  const { t } = useT();
  const ref = useScrollReveal();

  const badges = [
    { labelKey: "aboutTrustEncrypted", emoji: "🔒" },
    { labelKey: "aboutTrustNonCustodial", emoji: "🛡️" },
    { labelKey: "aboutTrustBinance", emoji: "🏦" },
    { labelKey: "aboutTrustUptime", emoji: "⏱" },
  ];

  return (
    <section
      id="security"
      className="scroll-mt-nav relative z-10 px-6 py-24 md:px-10"
      style={{
        background: "radial-gradient(ellipse 90% 70% at 75% 45%, rgba(200,180,255,0.18), transparent 80%)",
      }}
    >
      <div ref={ref} className="scroll-reveal mx-auto max-w-[1400px]">
        {/* Header: two-column — text left, shield illustration right */}
        <div className="grid grid-cols-1 items-center gap-8 md:grid-cols-[1fr_auto]">
          <div>
            <h2
              className="text-2xl font-normal tracking-tight text-black sm:text-3xl lg:text-4xl"
              style={{ letterSpacing: "-0.01em" }}
            >
              {t("landing.featureSecurityTitle")}
            </h2>
            <div
              className="mt-2 text-3xl font-extrabold uppercase tracking-tight text-black sm:text-7xl lg:text-9xl"
              style={{ letterSpacing: "-0.01em", lineHeight: 0.95 }}
            >
              {t("landing.securityWord")}
            </div>
            <p className="mt-3 text-sm text-gray-500 sm:text-base">
              {t("landing.securityNonCustodialIntro")}
            </p>
          </div>

          {/* Shield illustration — right of header */}
          <div className="hidden md:flex items-center justify-center">
            <SecurityIllustration />
          </div>
        </div>

        {/* Trust card */}
        <div className="relative mt-12 rounded-[20px] md:rounded-[30px] bg-white p-6 md:p-10 shadow-[0_1px_3px_rgba(0,0,0,0.05)]">
          <h3 className="text-2xl font-bold text-black">{t("landing.securityProtectedTitle")}</h3>
          <p className="mt-2 text-sm text-gray-500 leading-relaxed max-w-[520px]">
            {t("landing.featureNonCustodialDesc")}
          </p>
          <div className="mt-8 flex flex-wrap items-center gap-4">
            {badges.map((b) => (
              <div
                key={b.labelKey}
                className="flex items-center gap-2 rounded-2xl border border-black/[0.08] bg-white px-3 py-2 text-xs md:px-5 md:py-2.5 md:text-sm font-semibold text-gray-600"
                style={{ letterSpacing: "0.02em" }}
              >
                <span>{b.emoji}</span>
                {t(`landing.${b.labelKey}`)}
              </div>
            ))}
          </div>

          {/* Shield on mobile — shown below text */}
          <div className="mt-8 flex items-center justify-center md:hidden">
            <SecurityIllustration />
          </div>
        </div>
      </div>
    </section>
  );
}
