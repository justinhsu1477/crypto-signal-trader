"use client";

import { Sheet, SheetContent, SheetTrigger } from "@/components/ui/sheet";
import {
  Menu,
  LayoutDashboard,
  BarChart3,
  History,
  Link2,
  Settings,
  LogOut,
  Monitor,
  Users,
  ClipboardCheck,
} from "lucide-react";
import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { LanguageSwitcher } from "@/components/ui/language-switcher";
import { useState } from "react";

export function Header() {
  const pathname = usePathname();
  const { logout, email, role } = useAuth();
  const { t } = useT();
  const [open, setOpen] = useState(false);

  const navItems = [
    { href: "/", label: t("nav.overview"), icon: LayoutDashboard },
    { href: "/performance", label: t("nav.performance"), icon: BarChart3 },
    { href: "/trades", label: t("nav.trades"), icon: History },
    { href: "/referral", label: t("nav.referral"), icon: Link2 },
    { href: "/settings", label: t("nav.settings"), icon: Settings },
  ];

  const adminItems = [
    { href: "/admin", label: t("nav.adminOverview"), icon: Monitor },
    { href: "/admin/users", label: t("nav.adminUsers"), icon: Users },
    { href: "/admin/referrals", label: t("nav.adminReferrals"), icon: ClipboardCheck },
  ];

  const isAdmin = role === "ADMIN";

  return (
    <header className="md:hidden sticky top-0 z-50 flex items-center justify-between px-4 py-3 bg-card border-b border-border">
      <div className="flex items-center gap-2">
        <Image src="/logo.jpg" alt="HookFi" width={24} height={24} className="rounded-md" />
        <span className="font-bold">HookFi</span>
      </div>

      <div className="flex items-center gap-2">
        <LanguageSwitcher />
        <Sheet open={open} onOpenChange={setOpen}>
          <SheetTrigger asChild>
            <button className="p-2 rounded-lg hover:bg-accent">
              <Menu className="h-5 w-5" />
            </button>
          </SheetTrigger>
          <SheetContent side="left" className="w-64 p-0">
            <div className="flex items-center gap-2.5 px-6 py-5 border-b border-border">
              <Image src="/logo.jpg" alt="HookFi" width={28} height={28} className="rounded-lg" />
              <span className="text-lg font-bold">HookFi</span>
            </div>
            <nav className="px-3 py-4 space-y-1">
              {navItems.map((item) => {
                const isActive = pathname === item.href;
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    onClick={() => setOpen(false)}
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

              {/* Admin Section */}
              {isAdmin && (
                <>
                  <div className="pt-4 pb-1 px-3">
                    <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/60">
                      {t("nav.adminSection")}
                    </span>
                  </div>
                  {adminItems.map((item) => {
                    const isActive = pathname === item.href;
                    return (
                      <Link
                        key={item.href}
                        href={item.href}
                        onClick={() => setOpen(false)}
                        className={cn(
                          "flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors",
                          isActive
                            ? "bg-purple-500/20 text-purple-400"
                            : "text-muted-foreground hover:text-foreground hover:bg-accent/50"
                        )}
                      >
                        <item.icon className="h-5 w-5" />
                        {item.label}
                      </Link>
                    );
                  })}
                </>
              )}
            </nav>
            <div className="absolute bottom-0 left-0 right-0 border-t border-border px-3 py-4">
              <div className="px-3 mb-2 text-xs text-muted-foreground truncate">
                {email || "User"}
              </div>
              <button
                onClick={() => {
                  logout();
                  window.location.href = "/login";
                }}
                className="flex items-center gap-3 w-full px-3 py-2.5 rounded-lg text-sm text-muted-foreground hover:text-foreground hover:bg-accent/50"
              >
                <LogOut className="h-5 w-5" />
                {t("nav.logout")}
              </button>
            </div>
          </SheetContent>
        </Sheet>
      </div>
    </header>
  );
}
