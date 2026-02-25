"use client";

import { Mail, MessageCircle } from "lucide-react";
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
