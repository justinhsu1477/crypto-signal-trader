"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import {
  LayoutDashboard,
  BarChart3,
  History,
  Settings,
  LogOut,
  TrendingUp,
} from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { LanguageSwitcher } from "@/components/ui/language-switcher";

export function Sidebar() {
  const pathname = usePathname();
  const { logout, email } = useAuth();
  const { t } = useT();

  const navItems = [
    { href: "/", label: t("nav.overview"), icon: LayoutDashboard },
    { href: "/performance", label: t("nav.performance"), icon: BarChart3 },
    { href: "/trades", label: t("nav.trades"), icon: History },
    { href: "/settings", label: t("nav.settings"), icon: Settings },
  ];

  return (
    <aside className="hidden md:flex md:w-64 md:flex-col md:fixed md:inset-y-0 bg-card border-r border-border">
      {/* Logo */}
      <div className="flex items-center gap-2 px-6 py-5 border-b border-border">
        <TrendingUp className="h-6 w-6 text-emerald-500" />
        <span className="text-lg font-bold">Signal Trader</span>
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-3 py-4 space-y-1">
        {navItems.map((item) => {
          const isActive = pathname === item.href;
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors",
                isActive
                  ? "bg-accent text-accent-foreground"
                  : "text-muted-foreground hover:text-foreground hover:bg-accent/50"
              )}
            >
              <item.icon className="h-5 w-5" />
              {item.label}
            </Link>
          );
        })}
      </nav>

      {/* User & Logout */}
      <div className="border-t border-border px-3 py-4">
        <div className="flex items-center justify-between px-3 mb-2">
          <span className="text-xs text-muted-foreground truncate">
            {email || "User"}
          </span>
          <LanguageSwitcher />
        </div>
        <button
          onClick={() => {
            logout();
            window.location.href = "/login";
          }}
          className="flex items-center gap-3 w-full px-3 py-2.5 rounded-lg text-sm text-muted-foreground hover:text-foreground hover:bg-accent/50 transition-colors"
        >
          <LogOut className="h-5 w-5" />
          {t("nav.logout")}
        </button>
      </div>
    </aside>
  );
}
