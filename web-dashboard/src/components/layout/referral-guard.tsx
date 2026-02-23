"use client";

import { useReferralGuard } from "@/lib/use-referral-guard";

export function ReferralGuard({ children }: { children: React.ReactNode }) {
  const { isChecking } = useReferralGuard();

  if (isChecking) {
    return (
      <div className="flex h-screen items-center justify-center bg-background">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  return <>{children}</>;
}
