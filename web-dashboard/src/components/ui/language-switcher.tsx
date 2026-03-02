"use client";

import { useT } from "@/lib/i18n/i18n-context";
import { locales, localeLabels } from "@/lib/i18n/translations";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Globe } from "lucide-react";
import { usePathname } from "next/navigation";

export function LanguageSwitcher() {
  const { locale, setLocale } = useT();
  const pathname = usePathname();
  const isLanding = ["/login", "/register", "/verify-email"].includes(pathname);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          className={
            isLanding
              ? "flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium text-gray-600 hover:text-black hover:bg-black/[0.04] transition-colors outline-none"
              : "flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm text-muted-foreground hover:text-foreground hover:bg-accent/50 transition-colors outline-none"
          }
        >
          <Globe className="h-4 w-4" />
          <span className="hidden sm:inline">{localeLabels[locale]}</span>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className={isLanding ? "bg-white border-gray-200 text-black" : ""}>
        {locales.map((loc) => (
          <DropdownMenuItem
            key={loc}
            onClick={() => setLocale(loc)}
            className={`${locale === loc ? (isLanding ? "bg-gray-100 font-semibold" : "bg-accent") : ""} ${isLanding ? "text-gray-700 hover:text-black" : ""}`}
          >
            {localeLabels[loc]}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
