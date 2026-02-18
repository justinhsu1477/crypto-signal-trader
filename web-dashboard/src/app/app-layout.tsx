"use client";

import { usePathname } from "next/navigation";
import { AuthProvider } from "@/lib/auth-context";
import { AuthGuard } from "@/components/layout/auth-guard";
import { Sidebar } from "@/components/layout/sidebar";
import { Header } from "@/components/layout/header";

const PUBLIC_PATHS = ["/login", "/register"];

export function AppLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const isPublicPage = PUBLIC_PATHS.includes(pathname);

  return (
    <AuthProvider>
      {isPublicPage ? (
        children
      ) : (
        <AuthGuard>
          <div className="min-h-screen bg-background">
            <Sidebar />
            <div className="md:pl-64">
              <Header />
              <main className="p-4 md:p-6 lg:p-8">{children}</main>
            </div>
          </div>
        </AuthGuard>
      )}
    </AuthProvider>
  );
}
