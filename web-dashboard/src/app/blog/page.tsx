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
    <div className="relative min-h-screen overflow-hidden text-black" style={{ background: "rgb(255,248,247)" }}>
      <CryptoBackground />
      <PublicNavbar />

      <main className="relative z-10 pt-24 pb-16 px-4 md:px-6">
        <div className="max-w-4xl mx-auto">
          {/* Header */}
          <div className="text-center mb-16">
            <div className="inline-flex items-center gap-2 rounded-full border border-black/10 bg-black/[0.03] px-4 py-1.5 text-sm text-gray-600 mb-4">
              {t("blog.badge")}
            </div>
            <h1 className="text-3xl sm:text-4xl font-bold tracking-tight text-black">
              {t("blog.title")}
            </h1>
            <p className="mt-4 text-lg text-gray-500 max-w-2xl mx-auto">
              {t("blog.subtitle")}
            </p>
          </div>

          {/* Post cards */}
          <div className="space-y-6">
            {blogPosts.map((post) => (
              <Link
                key={post.slug}
                href={`/blog/${post.slug}`}
                className="group block rounded-2xl border border-black/[0.06] bg-white/60 p-6 hover:bg-white/80 hover:border-black/10 transition-all duration-300 shadow-[0_1px_3px_rgba(0,0,0,0.04)]"
              >
                <div className="flex gap-3 md:gap-5">
                  <div className="flex h-11 w-11 md:h-14 md:w-14 shrink-0 items-center justify-center rounded-xl bg-black/[0.04] text-xl md:text-2xl">
                    {post.coverEmoji}
                  </div>
                  <div className="flex-1 min-w-0">
                    <h2 className="text-base md:text-lg font-semibold text-black group-hover:text-gray-600 transition-colors line-clamp-2">
                      {post.title[loc]}
                    </h2>
                    <p className="mt-2 text-sm text-gray-500 leading-relaxed line-clamp-2">
                      {post.excerpt[loc]}
                    </p>
                    <div className="mt-3 flex items-center gap-3 md:gap-4 text-xs text-gray-400">
                      <span className="flex items-center gap-1">
                        <Calendar className="h-3 w-3" />
                        {post.date}
                      </span>
                      <span className="flex items-center gap-1">
                        <Clock className="h-3 w-3" />
                        {post.readMinutes} min
                      </span>
                      <div className="hidden sm:flex gap-1.5">
                        {post.tags.map((tag) => (
                          <span
                            key={tag}
                            className="rounded-full border border-black/[0.08] bg-black/[0.03] px-2 py-0.5 text-gray-500"
                          >
                            {tag}
                          </span>
                        ))}
                      </div>
                    </div>
                  </div>
                  <ArrowRight className="h-5 w-5 shrink-0 text-gray-400 group-hover:text-black transition-colors mt-1" />
                </div>
              </Link>
            ))}
          </div>
        </div>
      </main>

      {/* Footer */}
      <div className="relative z-10 border-t border-black/[0.06] py-6 text-center text-xs text-gray-400">
        <div className="flex items-center justify-center gap-1.5">
          <Image src="/logo.jpg" alt="HookFi" width={16} height={16} className="rounded-sm" />
          <span className="text-gray-600">HookFi</span>
          <span className="mx-2 text-gray-300">|</span>
          <span>{t("landing.footer")}</span>
        </div>
      </div>
    </div>
  );
}
