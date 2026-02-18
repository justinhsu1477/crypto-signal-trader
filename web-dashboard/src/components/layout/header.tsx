"use client";

import { Sheet, SheetContent, SheetTrigger } from "@/components/ui/sheet";
import { Menu, TrendingUp } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth-context";
import { LayoutDashboard, BarChart3, History, Settings, LogOut } from "lucide-react";
import { useState } from "react";

const navItems = [
  { href: "/", label: "總覽", icon: LayoutDashboard },
  { href: "/performance", label: "績效", icon: BarChart3 },
  { href: "/trades", label: "交易紀錄", icon: History },
  { href: "/settings", label: "設定", icon: Settings },
];

export function Header() {
  const pathname = usePathname();
  const { logout, email } = useAuth();
  const [open, setOpen] = useState(false);

  return (
    <header className="md:hidden sticky top-0 z-50 flex items-center justify-between px-4 py-3 bg-card border-b border-border">
      <div className="flex items-center gap-2">
        <TrendingUp className="h-5 w-5 text-emerald-500" />
        <span className="font-bold">Signal Trader</span>
      </div>

      <Sheet open={open} onOpenChange={setOpen}>
        <SheetTrigger asChild>
          <button className="p-2 rounded-lg hover:bg-accent">
            <Menu className="h-5 w-5" />
          </button>
        </SheetTrigger>
        <SheetContent side="left" className="w-64 p-0">
          <div className="flex items-center gap-2 px-6 py-5 border-b border-border">
            <TrendingUp className="h-6 w-6 text-emerald-500" />
            <span className="text-lg font-bold">Signal Trader</span>
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
              登出
            </button>
          </div>
        </SheetContent>
      </Sheet>
    </header>
  );
}
