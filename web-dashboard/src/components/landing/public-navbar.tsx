"use client";

import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { LanguageSwitcher } from "@/components/ui/language-switcher";
import { useT } from "@/lib/i18n/i18n-context";

/**
 * Lido-style transparent navbar: warm-bg blur, black text, pill-shaped black CTA.
 */
export function PublicNavbar() {
  const pathname = usePathname();
  const isLogin = pathname === "/login";
  const isLandingPage = ["/login", "/register", "/verify-email"].includes(pathname);
  const { t } = useT();

  const navLinks = [
    { href: "#features", label: t("nav.features") },
    { href: "#pricing", label: t("nav.pricing") },
    { href: "#security", label: t("landing.featureSecurityTitle") },
    { href: "/blog", label: "Blog" },
  ];

  return (
    <nav
      className="fixed top-0 left-0 right-0 z-50"
      style={{
        background: "rgba(255,249,249,0.8)",
        backdropFilter: "blur(20px)",
        WebkitBackdropFilter: "blur(20px)",
      }}
    >
      <div className="mx-auto flex h-16 max-w-[1400px] items-center justify-between px-10">
        {/* Logo */}
        <a
          href="/login"
          onClick={(e) => {
            if (isLandingPage) {
              e.preventDefault();
              window.scrollTo({ top: 0, behavior: "smooth" });
            }
          }}
          className="flex items-center gap-2.5 group cursor-pointer"
        >
          <Image
            src="/logo.jpg"
            alt="HookFi"
            width={32}
            height={32}
            className="rounded-md"
          />
          <span className="text-lg font-extrabold tracking-tight text-black" style={{ letterSpacing: "-0.03em" }}>
            HookFi
          </span>
        </a>

        {/* Center links */}
        <div className="hidden md:flex items-center gap-1">
          {navLinks.map((link) =>
            link.href.startsWith("/") ? (
              <Link
                key={link.href}
                href={link.href}
                className="px-5 py-2 text-base font-medium text-gray-500 hover:text-black transition-colors rounded-lg hover:bg-black/[0.04]"
              >
                {link.label}
              </Link>
            ) : isLandingPage ? (
              <a
                key={link.href}
                href={link.href}
                onClick={(e) => {
                  e.preventDefault();
                  document.querySelector(link.href)?.scrollIntoView({ behavior: "smooth" });
                }}
                className="px-5 py-2 text-base font-medium text-gray-500 hover:text-black transition-colors rounded-lg hover:bg-black/[0.04]"
              >
                {link.label}
              </a>
            ) : (
              <Link
                key={link.href}
                href={`/login${link.href}`}
                className="px-5 py-2 text-base font-medium text-gray-500 hover:text-black transition-colors rounded-lg hover:bg-black/[0.04]"
              >
                {link.label}
              </Link>
            )
          )}
        </div>

        {/* Right CTA */}
        <div className="flex items-center gap-3">
          <LanguageSwitcher />
          {isLogin ? (
            <Link
              href="/register"
              className="rounded-full bg-black px-6 py-2.5 text-sm font-bold text-white transition-colors hover:bg-gray-800"
            >
              {t("login.freeRegister")}
            </Link>
          ) : (
            <>
              <Link
                href="/login"
                className="px-4 py-2 text-sm font-medium text-gray-600 hover:text-black transition-colors"
              >
                {t("login.signIn")}
              </Link>
              <Link
                href="/register"
                className="rounded-full bg-black px-6 py-2.5 text-sm font-bold text-white transition-colors hover:bg-gray-800"
              >
                {t("login.freeRegister")}
              </Link>
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
