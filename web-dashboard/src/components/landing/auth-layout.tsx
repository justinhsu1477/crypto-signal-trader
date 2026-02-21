"use client";

import Image from "next/image";
import { PublicNavbar } from "./public-navbar";
import { CryptoBackground } from "./crypto-background";
import { BarChart3, Shield, Zap, Bot } from "lucide-react";
import { useT } from "@/lib/i18n/i18n-context";

export function AuthLayout({ children }: { children: React.ReactNode }) {
  const { t } = useT();

  const features = [
    { icon: Zap, title: t("landing.featureSignalTitle"), desc: t("landing.featureSignalDesc") },
    { icon: BarChart3, title: t("landing.featureRiskTitle"), desc: t("landing.featureRiskDesc") },
    { icon: Bot, title: t("landing.featureAiTitle"), desc: t("landing.featureAiDesc") },
    { icon: Shield, title: t("landing.featureSecurityTitle"), desc: t("landing.featureSecurityDesc") },
  ];

  return (
    <div className="min-h-screen bg-[#0a0a0a] text-foreground relative overflow-hidden">
      <CryptoBackground />

      <div className="fixed inset-0 pointer-events-none z-[1]">
        <div className="absolute top-0 left-1/4 w-[600px] h-[600px] bg-emerald-500/5 rounded-full blur-[120px]" />
        <div className="absolute bottom-0 right-1/4 w-[500px] h-[500px] bg-blue-500/5 rounded-full blur-[120px]" />
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[800px] h-[800px] bg-purple-500/3 rounded-full blur-[150px]" />
      </div>

      <PublicNavbar />

      <div className="relative z-10 flex min-h-screen flex-col lg:flex-row items-center justify-center gap-12 lg:gap-20 px-6 pt-24 pb-12 max-w-7xl mx-auto">
        <div className="flex-1 max-w-xl text-center lg:text-left">
          <div className="inline-flex items-center gap-2 rounded-full border border-emerald-500/20 bg-emerald-500/5 px-4 py-1.5 text-sm text-emerald-400 mb-6 animate-fade-in">
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500" />
            </span>
            {t("landing.statusBadge")}
          </div>

          <h1 className="text-4xl sm:text-5xl lg:text-6xl font-bold tracking-tight leading-tight animate-fade-in-up">
            {t("landing.heroTitle1")}
            <br />
            <span className="bg-gradient-to-r from-emerald-400 via-blue-400 to-purple-400 bg-clip-text text-transparent">
              {t("landing.heroTitle2")}
            </span>
          </h1>

          <p className="mt-5 text-lg text-muted-foreground leading-relaxed max-w-md mx-auto lg:mx-0 animate-fade-in-up animation-delay-200">
            {t("landing.heroDescription")}
          </p>

          <div className="grid grid-cols-2 gap-3 mt-8 animate-fade-in-up animation-delay-400">
            {features.map((f) => (
              <div
                key={f.title}
                className="flex items-start gap-3 rounded-xl border border-white/5 bg-white/[0.02] p-3 hover:bg-white/[0.04] transition-colors"
              >
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-emerald-500/10">
                  <f.icon className="h-4 w-4 text-emerald-400" />
                </div>
                <div>
                  <p className="text-sm font-medium">{f.title}</p>
                  <p className="text-xs text-muted-foreground mt-0.5 leading-relaxed">
                    {f.desc}
                  </p>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="w-full max-w-[420px] animate-fade-in-up animation-delay-300">
          {children}
        </div>
      </div>

      <div className="relative z-10 border-t border-white/5 py-6 text-center text-xs text-muted-foreground">
        <div className="flex items-center justify-center gap-1.5">
          <Image src="/logo.jpg" alt="HookFi" width={16} height={16} className="rounded-sm" />
          <span>HookFi</span>
          <span className="mx-2 text-white/10">|</span>
          <span>{t("landing.footer")}</span>
        </div>
      </div>
    </div>
  );
}
