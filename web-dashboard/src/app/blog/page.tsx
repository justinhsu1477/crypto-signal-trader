"use client";

import Link from "next/link";
import Image from "next/image";
import { ArrowRight, Clock, Calendar } from "lucide-react";
import { PublicNavbar } from "@/components/landing/public-navbar";
import { CryptoBackground } from "@/components/landing/crypto-background";
import { useT } from "@/lib/i18n/i18n-context";
import { blogPosts } from "@/lib/blog-data";
import type { Locale } from "@/lib/i18n/translations";

export default function BlogPage() {
  const { t, locale } = useT();
  const loc = locale as Locale;

  return (
    <div className="min-h-screen bg-[#0a0a0a] text-foreground relative overflow-hidden">
      <CryptoBackground />

      <div className="fixed inset-0 pointer-events-none z-[1]">
        <div className="absolute top-0 left-1/4 w-[600px] h-[600px] bg-emerald-500/5 rounded-full blur-[120px]" />
        <div className="absolute bottom-0 right-1/4 w-[500px] h-[500px] bg-blue-500/5 rounded-full blur-[120px]" />
      </div>

      <PublicNavbar />

      <main className="relative z-10 pt-28 pb-20 px-6">
        <div className="max-w-4xl mx-auto">
          {/* Header */}
          <div className="text-center mb-16">
            <div className="inline-flex items-center gap-2 rounded-full border border-emerald-500/20 bg-emerald-500/5 px-4 py-1.5 text-sm text-emerald-400 mb-4">
              {t("blog.badge")}
            </div>
            <h1 className="text-3xl sm:text-4xl font-bold tracking-tight">
              {t("blog.title")}
            </h1>
            <p className="mt-4 text-lg text-muted-foreground max-w-2xl mx-auto">
              {t("blog.subtitle")}
            </p>
          </div>

          {/* Post cards */}
          <div className="space-y-6">
            {blogPosts.map((post) => (
              <Link
                key={post.slug}
                href={`/blog/${post.slug}`}
                className="group block rounded-2xl border border-white/5 bg-white/[0.02] p-6 hover:bg-white/[0.04] hover:border-emerald-500/20 transition-all duration-300"
              >
                <div className="flex gap-5">
                  <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-xl bg-emerald-500/10 text-2xl">
                    {post.coverEmoji}
                  </div>
                  <div className="flex-1 min-w-0">
                    <h2 className="text-lg font-semibold group-hover:text-emerald-400 transition-colors line-clamp-2">
                      {post.title[loc]}
                    </h2>
                    <p className="mt-2 text-sm text-muted-foreground leading-relaxed line-clamp-2">
                      {post.excerpt[loc]}
                    </p>
                    <div className="mt-3 flex items-center gap-4 text-xs text-muted-foreground">
                      <span className="flex items-center gap-1">
                        <Calendar className="h-3 w-3" />
                        {post.date}
                      </span>
                      <span className="flex items-center gap-1">
                        <Clock className="h-3 w-3" />
                        {post.readMinutes} min
                      </span>
                      <div className="flex gap-1.5">
                        {post.tags.map((tag) => (
                          <span
                            key={tag}
                            className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5"
                          >
                            {tag}
                          </span>
                        ))}
                      </div>
                    </div>
                  </div>
                  <ArrowRight className="h-5 w-5 shrink-0 text-muted-foreground group-hover:text-emerald-400 transition-colors mt-1" />
                </div>
              </Link>
            ))}
          </div>
        </div>
      </main>

      {/* Footer */}
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
