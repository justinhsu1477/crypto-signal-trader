"use client";

import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { Button } from "@/components/ui/button";
import { LanguageSwitcher } from "@/components/ui/language-switcher";
import { useT } from "@/lib/i18n/i18n-context";

export function PublicNavbar() {
  const pathname = usePathname();
  const isLogin = pathname === "/login";
  const { t } = useT();

  const navLinks = [
    { href: "#features", label: t("nav.features") },
    { href: "#pricing", label: t("nav.pricing") },
    { href: "#about", label: t("nav.about") },
    { href: "#contact", label: t("nav.contact") },
  ];

  return (
    <nav className="fixed top-0 left-0 right-0 z-50 border-b border-white/5 bg-black/60 backdrop-blur-xl">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6">
        {/* Logo */}
        <a
          href="#"
          onClick={(e) => {
            e.preventDefault();
            window.scrollTo({ top: 0, behavior: "smooth" });
          }}
          className="flex items-center gap-2.5 group cursor-pointer"
        >
          <Image
            src="/logo.jpg"
            alt="HookFi"
            width={36}
            height={36}
            className="rounded-lg"
          />
          <span className="text-lg font-bold tracking-tight">
            HookFi
          </span>
        </a>

        {/* Center links */}
        <div className="hidden md:flex items-center gap-1">
          {navLinks.map((link) => (
            <a
              key={link.href}
              href={link.href}
              onClick={(e) => {
                e.preventDefault();
                document.querySelector(link.href)?.scrollIntoView({ behavior: "smooth" });
              }}
              className="px-4 py-2 text-sm text-muted-foreground hover:text-foreground transition-colors rounded-md hover:bg-white/5"
            >
              {link.label}
            </a>
          ))}
        </div>

        {/* Right CTA */}
        <div className="flex items-center gap-3">
          <LanguageSwitcher />
          {isLogin ? (
            <Button asChild variant="default" size="sm" className="bg-emerald-600 hover:bg-emerald-500 text-white">
              <Link href="/register">{t("login.freeRegister")}</Link>
            </Button>
          ) : (
            <>
              <Button asChild variant="ghost" size="sm">
                <Link href="/login">{t("login.signIn")}</Link>
              </Button>
              <Button asChild variant="default" size="sm" className="bg-emerald-600 hover:bg-emerald-500 text-white">
                <Link href="/register">{t("login.freeRegister")}</Link>
              </Button>
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
