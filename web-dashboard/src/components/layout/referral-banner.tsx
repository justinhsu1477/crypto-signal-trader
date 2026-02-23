"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useT } from "@/lib/i18n/i18n-context";
import { Button } from "@/components/ui/button";
import { AlertTriangle, X } from "lucide-react";

export function ReferralBanner() {
  const { t } = useT();
  const router = useRouter();
  const [show, setShow] = useState(false);

  useEffect(() => {
    function handleReferralBlocked() {
      setShow(true);
    }
    window.addEventListener("referral-not-verified", handleReferralBlocked);
    return () => {
      window.removeEventListener("referral-not-verified", handleReferralBlocked);
    };
  }, []);

  if (!show) return null;

  return (
    <div className="sticky top-0 z-50 flex items-center justify-between gap-3 bg-yellow-950/80 border-b border-yellow-900 px-4 py-2.5 text-sm text-yellow-300 backdrop-blur">
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-4 w-4 shrink-0" />
        <span>{t("referral.bannerMessage")}</span>
      </div>
      <div className="flex items-center gap-2">
        <Button
          size="sm"
          variant="outline"
          className="h-7 text-xs border-yellow-700 text-yellow-300 hover:bg-yellow-900"
          onClick={() => {
            setShow(false);
            router.push("/referral");
          }}
        >
          {t("referral.bannerAction")}
        </Button>
        <button
          onClick={() => setShow(false)}
          className="text-yellow-500 hover:text-yellow-300"
        >
          <X className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}
