"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { TrendingUp } from "lucide-react";
import { Button } from "@/components/ui/button";

const NAV_LINKS = [
  { href: "#features", label: "功能特色" },
  { href: "#pricing", label: "方案價格" },
  { href: "#about", label: "關於我們" },
  { href: "#contact", label: "聯繫我們" },
];

export function PublicNavbar() {
  const pathname = usePathname();
  const isLogin = pathname === "/login";

  return (
    <nav className="fixed top-0 left-0 right-0 z-50 border-b border-white/5 bg-black/60 backdrop-blur-xl">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6">
        {/* Logo */}
        <Link href="/login" className="flex items-center gap-2.5 group">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-emerald-500/10 border border-emerald-500/20 group-hover:bg-emerald-500/20 transition-colors">
            <TrendingUp className="h-5 w-5 text-emerald-400" />
          </div>
          <span className="text-lg font-bold tracking-tight">
            Signal Trader
          </span>
        </Link>

        {/* Center links */}
        <div className="hidden md:flex items-center gap-1">
          {NAV_LINKS.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className="px-4 py-2 text-sm text-muted-foreground hover:text-foreground transition-colors rounded-md hover:bg-white/5"
            >
              {link.label}
            </a>
          ))}
        </div>

        {/* Right CTA */}
        <div className="flex items-center gap-3">
          {isLogin ? (
            <Button asChild variant="default" size="sm" className="bg-emerald-600 hover:bg-emerald-500 text-white">
              <Link href="/register">免費註冊</Link>
            </Button>
          ) : (
            <>
              <Button asChild variant="ghost" size="sm">
                <Link href="/login">登入</Link>
              </Button>
              <Button asChild variant="default" size="sm" className="bg-emerald-600 hover:bg-emerald-500 text-white">
                <Link href="/register">免費註冊</Link>
              </Button>
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
