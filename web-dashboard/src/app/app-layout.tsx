"use client";

import { usePathname } from "next/navigation";
import { AuthProvider } from "@/lib/auth-context";
import { I18nProvider } from "@/lib/i18n/i18n-context";
import { AuthGuard } from "@/components/layout/auth-guard";
import { ReferralGuard } from "@/components/layout/referral-guard";
import { Sidebar } from "@/components/layout/sidebar";
import { Header } from "@/components/layout/header";
import { AuthLayout } from "@/components/landing/auth-layout";
import { ErrorBoundary, PageErrorFallback } from "@/components/ui/error-boundary";
import { Toaster } from "sonner";

const PUBLIC_PATHS = ["/login", "/register", "/verify-email"];

export function AppLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const isPublicPage = PUBLIC_PATHS.includes(pathname);

  return (
    <ErrorBoundary fallback={<PageErrorFallback />}>
      <Toaster richColors position="top-right" theme="dark" />
      <AuthProvider>
        <I18nProvider>
          {isPublicPage ? (
            <AuthLayout>{children}</AuthLayout>
          ) : (
            <AuthGuard>
              <ReferralGuard>
                <div className="min-h-screen bg-background">
                  <Sidebar />
                  <div className="md:pl-64">
                    <Header />
                    <main className="p-4 md:p-6 lg:p-8">{children}</main>
                  </div>
                </div>
              </ReferralGuard>
            </AuthGuard>
          )}
        </I18nProvider>
      </AuthProvider>
    </ErrorBoundary>
  );
}
