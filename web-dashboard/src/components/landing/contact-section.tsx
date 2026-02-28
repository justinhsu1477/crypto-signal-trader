"use client";

import { Mail, MessageCircle, Send } from "lucide-react";
import { useT } from "@/lib/i18n/i18n-context";

export function ContactSection() {
  const { t } = useT();

  return (
    <section id="contact" className="scroll-mt-nav relative z-10 py-24 px-6">
      <div className="max-w-4xl mx-auto text-center">
        <div className="inline-flex items-center gap-2 rounded-full border border-emerald-500/20 bg-emerald-500/5 px-4 py-1.5 text-sm text-emerald-400 mb-4">
          {t("landing.contactBadge")}
        </div>
        <h2 className="text-3xl sm:text-4xl font-bold tracking-tight">
          {t("landing.contactTitle")}
        </h2>
        <p className="mt-4 text-lg text-muted-foreground max-w-xl mx-auto">
          {t("landing.contactSubtitle")}
        </p>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-6 mt-12">
          <a
            href="mailto:support@hook-fi.com"
            className="rounded-2xl border border-white/5 bg-white/[0.02] p-8 hover:bg-white/[0.04] hover:border-emerald-500/20 transition-all group"
          >
            <Mail className="h-8 w-8 text-emerald-400 mx-auto mb-4" />
            <h3 className="text-lg font-semibold">{t("landing.contactEmail")}</h3>
            <p className="text-sm text-muted-foreground mt-2">support@hook-fi.com</p>
            <p className="text-xs text-muted-foreground mt-1">{t("landing.contactEmailDesc")}</p>
          </a>

          <a
            href="https://lin.ee/9ga4egy"
            target="_blank"
            rel="noopener noreferrer"
            className="rounded-2xl border border-white/5 bg-white/[0.02] p-8 hover:bg-white/[0.04] hover:border-green-500/20 transition-all group"
          >
            <svg className="h-8 w-8 mx-auto mb-4" viewBox="0 0 24 24" fill="currentColor">
              <path className="text-green-400" d="M19.365 9.863c.349 0 .63.285.63.631 0 .345-.281.63-.63.63H17.61v1.125h1.755c.349 0 .63.283.63.63 0 .344-.281.629-.63.629h-2.386c-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63h2.386c.346 0 .627.285.627.63 0 .349-.281.63-.63.63H17.61v1.125h1.755zm-3.855 3.016c0 .27-.174.51-.432.596-.064.021-.133.031-.199.031-.211 0-.391-.09-.51-.25l-2.443-3.317v2.94c0 .344-.279.629-.631.629-.346 0-.626-.285-.626-.629V8.108c0-.27.173-.51.43-.595.06-.023.136-.033.194-.033.195 0 .375.104.495.254l2.462 3.33V8.108c0-.345.282-.63.63-.63.345 0 .63.285.63.63v4.771zm-5.741 0c0 .344-.282.629-.631.629-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63.346 0 .628.285.628.63v4.771zm-2.466.629H4.917c-.345 0-.63-.285-.63-.629V8.108c0-.345.285-.63.63-.63.348 0 .63.285.63.63v4.141h1.756c.348 0 .629.283.629.63 0 .344-.282.629-.629.629M24 10.314C24 4.943 18.615.572 12 .572S0 4.943 0 10.314c0 4.811 4.27 8.842 10.035 9.608.391.082.923.258 1.058.59.12.301.079.766.038 1.08l-.164 1.02c-.045.301-.24 1.186 1.049.645 1.291-.539 6.916-4.078 9.436-6.975C23.176 14.393 24 12.458 24 10.314" />
            </svg>
            <h3 className="text-lg font-semibold">{t("landing.contactLine")}</h3>
            <p className="text-sm text-muted-foreground mt-2">{t("landing.contactLineDesc")}</p>
          </a>

          <a
            href="https://t.me/hookfi_support"
            target="_blank"
            rel="noopener noreferrer"
            className="rounded-2xl border border-white/5 bg-white/[0.02] p-8 hover:bg-white/[0.04] hover:border-sky-500/20 transition-all group"
          >
            <Send className="h-8 w-8 text-sky-400 mx-auto mb-4" />
            <h3 className="text-lg font-semibold">{t("landing.contactTelegram")}</h3>
            <p className="text-sm text-muted-foreground mt-2">{t("landing.contactTelegramDesc")}</p>
          </a>

          <a
            href="https://discord.gg/hookfi"
            target="_blank"
            rel="noopener noreferrer"
            className="rounded-2xl border border-white/5 bg-white/[0.02] p-8 hover:bg-white/[0.04] hover:border-blue-500/20 transition-all group"
          >
            <MessageCircle className="h-8 w-8 text-blue-400 mx-auto mb-4" />
            <h3 className="text-lg font-semibold">{t("landing.contactDiscord")}</h3>
            <p className="text-sm text-muted-foreground mt-2">{t("landing.contactDiscordDesc")}</p>
          </a>
        </div>
      </div>
    </section>
  );
}
