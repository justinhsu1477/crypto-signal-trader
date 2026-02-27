"use client";

import { useParams } from "next/navigation";
import Link from "next/link";
import Image from "next/image";
import { ArrowLeft, Clock, Calendar } from "lucide-react";
import { PublicNavbar } from "@/components/landing/public-navbar";
import { CryptoBackground } from "@/components/landing/crypto-background";
import { useT } from "@/lib/i18n/i18n-context";
import { blogPosts } from "@/lib/blog-data";
import type { Locale } from "@/lib/i18n/translations";

/** Minimal markdown-to-HTML: headings, bold, lists, paragraphs */
function renderMarkdown(md: string): string {
  return md
    .split("\n\n")
    .map((block) => {
      const trimmed = block.trim();
      if (!trimmed) return "";

      // Headings
      if (trimmed.startsWith("## "))
        return `<h2 class="text-xl font-bold mt-10 mb-4">${trimmed.slice(3)}</h2>`;
      if (trimmed.startsWith("### "))
        return `<h3 class="text-lg font-semibold mt-8 mb-3">${trimmed.slice(4)}</h3>`;

      // Unordered list
      if (trimmed.startsWith("- ")) {
        const items = trimmed
          .split("\n")
          .filter((l) => l.startsWith("- "))
          .map((l) => `<li class="ml-4 list-disc text-muted-foreground leading-relaxed">${inlineFmt(l.slice(2))}</li>`)
          .join("");
        return `<ul class="space-y-1 my-4">${items}</ul>`;
      }

      // Ordered list
      if (/^\d+\.\s/.test(trimmed)) {
        const items = trimmed
          .split("\n")
          .filter((l) => /^\d+\.\s/.test(l))
          .map((l) => `<li class="ml-4 list-decimal text-muted-foreground leading-relaxed">${inlineFmt(l.replace(/^\d+\.\s/, ""))}</li>`)
          .join("");
        return `<ol class="space-y-1 my-4">${items}</ol>`;
      }

      // Paragraph
      return `<p class="text-muted-foreground leading-relaxed my-4">${inlineFmt(trimmed)}</p>`;
    })
    .join("\n");
}

function inlineFmt(text: string): string {
  return text.replace(/\*\*(.+?)\*\*/g, '<strong class="text-foreground font-semibold">$1</strong>');
}

export default function BlogPostPage() {
  const params = useParams();
  const slug = params.slug as string;
  const { t, locale } = useT();
  const loc = locale as Locale;

  const post = blogPosts.find((p) => p.slug === slug);

  if (!post) {
    return (
      <div className="min-h-screen bg-[#0a0a0a] text-foreground flex items-center justify-center">
        <div className="text-center">
          <h1 className="text-2xl font-bold mb-4">Post not found</h1>
          <Link href="/blog" className="text-emerald-400 hover:underline">
            &larr; {t("blog.backToList")}
          </Link>
        </div>
      </div>
    );
  }

  const html = renderMarkdown(post.content[loc]);

  return (
    <div className="min-h-screen bg-[#0a0a0a] text-foreground relative overflow-hidden">
      <CryptoBackground />

      <div className="fixed inset-0 pointer-events-none z-[1]">
        <div className="absolute top-0 left-1/4 w-[600px] h-[600px] bg-emerald-500/5 rounded-full blur-[120px]" />
        <div className="absolute bottom-0 right-1/4 w-[500px] h-[500px] bg-blue-500/5 rounded-full blur-[120px]" />
      </div>

      <PublicNavbar />

      <main className="relative z-10 pt-28 pb-20 px-6">
        <article className="max-w-3xl mx-auto">
          {/* Back link */}
          <Link
            href="/blog"
            className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-emerald-400 transition-colors mb-8"
          >
            <ArrowLeft className="h-4 w-4" />
            {t("blog.backToList")}
          </Link>

          {/* Header */}
          <div className="mb-10">
            <div className="flex items-center gap-3 mb-4">
              <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-emerald-500/10 text-2xl">
                {post.coverEmoji}
              </div>
              <div className="flex gap-1.5">
                {post.tags.map((tag) => (
                  <span
                    key={tag}
                    className="rounded-full border border-white/10 bg-white/5 px-2.5 py-0.5 text-xs text-muted-foreground"
                  >
                    {tag}
                  </span>
                ))}
              </div>
            </div>

            <h1 className="text-2xl sm:text-3xl font-bold tracking-tight leading-tight">
              {post.title[loc]}
            </h1>

            <div className="mt-4 flex items-center gap-4 text-sm text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <Calendar className="h-4 w-4" />
                {post.date}
              </span>
              <span className="flex items-center gap-1.5">
                <Clock className="h-4 w-4" />
                {post.readMinutes} min read
              </span>
            </div>
          </div>

          {/* Content */}
          <div
            className="prose-custom"
            dangerouslySetInnerHTML={{ __html: html }}
          />

          {/* CTA */}
          <div className="mt-16 rounded-2xl border border-emerald-500/20 bg-emerald-500/5 p-8 text-center">
            <h3 className="text-xl font-bold mb-2">{t("blog.ctaTitle")}</h3>
            <p className="text-sm text-muted-foreground mb-5">
              {t("blog.ctaDescription")}
            </p>
            <Link
              href="/register"
              className="inline-flex items-center gap-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 px-6 py-2.5 text-sm font-medium text-white transition-colors"
            >
              {t("blog.ctaButton")}
              <ArrowLeft className="h-4 w-4 rotate-180" />
            </Link>
          </div>
        </article>
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
