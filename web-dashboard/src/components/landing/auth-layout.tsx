"use client";

import Image from "next/image";
import Link from "next/link";
import { Suspense, useState } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { PublicNavbar } from "./public-navbar";
import { CryptoBackground } from "./crypto-background";
import { HeroOrbitVisual } from "./hero-orbit-visual";
import { StatsBarSection } from "./stats-bar-section";
import { FeaturesSection } from "./features-section";
import { HowItWorksSection } from "./how-it-works-section";
import { PricingSection } from "./pricing-section";
import { Mail, MessageCircle, Send } from "lucide-react";
import { useT } from "@/lib/i18n/i18n-context";

/**
 * Lido-style landing layout: light warm theme, Manrope-inspired typography.
 */
export function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <Suspense>
      <AuthLayoutInner>{children}</AuthLayoutInner>
    </Suspense>
  );
}

function AuthLayoutInner({ children }: { children: React.ReactNode }) {
  const { t } = useT();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const isLogin = pathname === "/login";
  const initialShowCard = searchParams.get("action") === "signin";
  const [showAuthCard, setShowAuthCard] = useState(initialShowCard);

  return (
    <div className="relative min-h-screen overflow-x-hidden text-black" style={{ background: "rgb(255,248,247)" }}>
      <CryptoBackground />
      <PublicNavbar />

      {/* ── Hero (two-column like lido.fi) ── */}
      <main className="relative z-10 mx-auto grid min-h-[100dvh] max-w-[1400px] grid-cols-1 items-center gap-16 px-6 pt-20 md:grid-cols-2 md:px-10">
        {/* Left: Logo */}
        <div className="flex items-center justify-center animate-fade-in-scale md:order-first order-first">
          <HeroOrbitVisual />
        </div>

        {/* Right: Text + CTA or Auth card */}
        <div>
          {showAuthCard ? (
            <div className="animate-fade-in-up w-full max-w-[420px]">
              <button
                type="button"
                onClick={() => setShowAuthCard(false)}
                className="mb-3 text-sm text-gray-400 transition-colors hover:text-black"
              >
                &larr; {t("landing.backToIntro")}
              </button>
              {children}
            </div>
          ) : (
            <div className="animate-fade-in-up animation-delay-200">
              <h1
                className="text-4xl font-normal leading-none tracking-tight text-black sm:text-5xl lg:text-6xl"
                style={{ letterSpacing: "-0.01em", lineHeight: 1.0 }}
              >
                {t("landing.heroTitle1")}{" "}
                <strong className="block text-5xl font-extrabold sm:text-6xl lg:text-7xl">
                  {t("landing.heroTitle2")}
                </strong>
              </h1>

              <p className="mt-5 max-w-[440px] text-base leading-relaxed text-gray-500 sm:text-lg">
                {t("landing.heroDescription")}
              </p>

              {/* Inline stats */}
              <div className="mt-9 animate-fade-in-up animation-delay-300">
                <StatsBarSection />
              </div>

              {/* CTA buttons */}
              <div className="mt-9 flex items-center gap-4 animate-fade-in-up animation-delay-400">
                <button
                  onClick={() => setShowAuthCard(true)}
                  className="inline-flex items-center gap-2 rounded-full bg-black px-8 py-3.5 text-base font-bold text-white transition-all hover:bg-gray-800 hover:-translate-y-0.5"
                >
                  {t("landing.startButton")}
                </button>
                <a
                  href="#features"
                  onClick={(e) => {
                    e.preventDefault();
                    document.querySelector("#features")?.scrollIntoView({ behavior: "smooth" });
                  }}
                  className="inline-flex items-center gap-2 rounded-full border border-gray-300 px-8 py-3.5 text-base font-bold text-black transition-all hover:border-gray-400"
                >
                  {t("landing.heroLearnMore")}
                </a>
              </div>

              <p className="mt-3 text-sm text-gray-400">
                {isLogin ? t("landing.startHintLogin") : t("landing.startHintRegister")}
              </p>
            </div>
          )}
        </div>
      </main>

      {/* ── Sections ── */}
      <PricingSection />
      <FeaturesSection />
      <HowItWorksSection />

      {/* ── Footer (enlarged, lido-style with columns) ── */}
      <footer className="relative z-10 border-t border-black/[0.08] px-6 py-16 md:px-10 md:py-20">
        <div className="mx-auto max-w-[1400px]">
          {/* Top row: Brand + nav columns */}
          <div className="grid grid-cols-1 gap-12 sm:grid-cols-2 lg:grid-cols-4">
            {/* Brand column */}
            <div className="lg:col-span-1">
              <div className="flex items-center gap-3 mb-4">
                <Image src="/logo.jpg" alt="HookFi" width={36} height={36} className="rounded-lg" />
                <span className="text-lg font-extrabold text-black" style={{ letterSpacing: "-0.03em" }}>HookFi</span>
              </div>
              <p className="text-sm text-gray-400 leading-relaxed max-w-[260px]">
                {t("landing.footer")}
              </p>
            </div>

            {/* Product column */}
            <div>
              <h4 className="text-sm font-bold text-black mb-4">{t("landing.featuresTitle")}</h4>
              <ul className="space-y-2.5">
                <li><a href="#features" className="text-sm text-gray-500 hover:text-black transition-colors">{t("landing.featureAutoExecTitle")}</a></li>
                <li><a href="#features" className="text-sm text-gray-500 hover:text-black transition-colors">{t("landing.featureRiskMgmtTitle")}</a></li>
                <li><a href="#features" className="text-sm text-gray-500 hover:text-black transition-colors">{t("landing.featureDcaTitle")}</a></li>
                <li><a href="#features" className="text-sm text-gray-500 hover:text-black transition-colors">{t("landing.featureAnalyticsTitle")}</a></li>
              </ul>
            </div>

            {/* Resources column */}
            <div>
              <h4 className="text-sm font-bold text-black mb-4">{t("landing.aboutBadge")}</h4>
              <ul className="space-y-2.5">
                <li><a href="#pricing" className="text-sm text-gray-500 hover:text-black transition-colors">{t("landing.pricingBadge")}</a></li>
                <li><a href="#security" className="text-sm text-gray-500 hover:text-black transition-colors">{t("landing.featureSecurityTitle")}</a></li>
                <li><Link href="/blog" className="text-sm text-gray-500 hover:text-black transition-colors">Blog</Link></li>
              </ul>
            </div>

            {/* Contact column */}
            <div>
              <h4 className="text-sm font-bold text-black mb-4">Contact</h4>
              <ul className="space-y-2.5">
                <li>
                  <a href="mailto:support@hook-fi.com" className="flex items-center gap-2 text-sm text-gray-500 hover:text-black transition-colors">
                    <Mail className="h-4 w-4" /> support@hook-fi.com
                  </a>
                </li>
                <li>
                  <a href="https://lin.ee/9ga4egy" target="_blank" rel="noopener noreferrer" className="flex items-center gap-2 text-sm text-gray-500 hover:text-black transition-colors">
                    <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M19.365 9.863c.349 0 .63.285.63.631 0 .345-.281.63-.63.63H17.61v1.125h1.755c.349 0 .63.283.63.63 0 .344-.281.629-.63.629h-2.386c-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63h2.386c.346 0 .627.285.627.63 0 .349-.281.63-.63.63H17.61v1.125h1.755zm-3.855 3.016c0 .27-.174.51-.432.596-.064.021-.133.031-.199.031-.211 0-.391-.09-.51-.25l-2.443-3.317v2.94c0 .344-.279.629-.631.629-.346 0-.626-.285-.626-.629V8.108c0-.27.173-.51.43-.595.06-.023.136-.033.194-.033.195 0 .375.104.495.254l2.462 3.33V8.108c0-.345.282-.63.63-.63.345 0 .63.285.63.63v4.771zm-5.741 0c0 .344-.282.629-.631.629-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63.346 0 .628.285.628.63v4.771zm-2.466.629H4.917c-.345 0-.63-.285-.63-.629V8.108c0-.345.285-.63.63-.63.348 0 .63.285.63.63v4.141h1.756c.348 0 .629.283.629.63 0 .344-.282.629-.629.629M24 10.314C24 4.943 18.615.572 12 .572S0 4.943 0 10.314c0 4.811 4.27 8.842 10.035 9.608.391.082.923.258 1.058.59.12.301.079.766.038 1.08l-.164 1.02c-.045.301-.24 1.186 1.049.645 1.291-.539 6.916-4.078 9.436-6.975C23.176 14.393 24 12.458 24 10.314" />
                    </svg>
                    LINE
                  </a>
                </li>
                <li>
                  <a href="https://t.me/hookfi" target="_blank" rel="noopener noreferrer" className="flex items-center gap-2 text-sm text-gray-500 hover:text-black transition-colors">
                    <Send className="h-4 w-4" /> Telegram
                  </a>
                </li>
                <li>
                  <a href="https://discord.gg/hookfi" target="_blank" rel="noopener noreferrer" className="flex items-center gap-2 text-sm text-gray-500 hover:text-black transition-colors">
                    <MessageCircle className="h-4 w-4" /> Discord
                  </a>
                </li>
              </ul>
            </div>
          </div>

          {/* Bottom row: copyright */}
          <div className="mt-12 border-t border-black/[0.06] pt-6 flex flex-col items-center gap-3 sm:flex-row sm:justify-between">
            <p className="text-xs text-gray-400">&copy; {new Date().getFullYear()} HookFi. All rights reserved.</p>
            <div className="flex items-center gap-4">
              <a href="/legal" className="text-xs text-gray-400 hover:text-black transition-colors">Privacy Policy</a>
              <a href="/legal" className="text-xs text-gray-400 hover:text-black transition-colors">Terms of Service</a>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}
