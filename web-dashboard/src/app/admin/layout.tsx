"use client";

import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { useEffect } from "react";

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const { role, isLoading } = useAuth();
  const { t } = useT();
  const router = useRouter();

  useEffect(() => {
    if (!isLoading && role !== "ADMIN") {
      router.replace("/");
    }
  }, [isLoading, role, router]);

  if (isLoading) {
    return (
      <div className="flex h-[60vh] items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  if (role !== "ADMIN") {
    return (
      <div className="flex h-[60vh] items-center justify-center text-muted-foreground">
        {t("admin.noAccess")}
      </div>
    );
  }

  return <>{children}</>;
}
