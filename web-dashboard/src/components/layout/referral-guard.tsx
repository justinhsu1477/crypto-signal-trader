"use client";

import { useRouter } from "next/navigation";
import { AlertTriangle } from "lucide-react";
import { useReferralGuard } from "@/lib/use-referral-guard";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";

export function ReferralGuard({ children }: { children: React.ReactNode }) {
  const { role } = useAuth();
  const { isChecking, needsReferral } = useReferralGuard(role);
  const { t } = useT();
  const router = useRouter();

  if (isChecking) {
    return (
      <div className="flex h-screen items-center justify-center bg-background">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  return (
    <>
      {children}

      <Dialog open={needsReferral} onOpenChange={() => {}}>
        <DialogContent
          showCloseButton={false}
          onPointerDownOutside={(e) => e.preventDefault()}
          onEscapeKeyDown={(e) => e.preventDefault()}
          className="sm:max-w-md"
        >
          <DialogHeader className="items-center sm:items-start">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-yellow-100 dark:bg-yellow-900/30 mb-2">
              <AlertTriangle className="h-6 w-6 text-yellow-600 dark:text-yellow-400" />
            </div>
            <DialogTitle>{t("referral.guardTitle")}</DialogTitle>
            <DialogDescription>
              {t("referral.guardDescription")}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter className="sm:justify-center">
            <Button
              className="w-full sm:w-auto"
              onClick={() => router.push("/referral")}
            >
              {t("referral.guardAction")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
