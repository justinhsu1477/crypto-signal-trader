"use client";

import { useState, useRef, useCallback } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { AlertTriangle, Shield, FileText, ChevronDown } from "lucide-react";
import { useT } from "@/lib/i18n/i18n-context";

interface LegalDisclaimerDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAgree: () => void;
}

export function LegalDisclaimerDialog({
  open,
  onOpenChange,
  onAgree,
}: LegalDisclaimerDialogProps) {
  const { t } = useT();
  const [hasScrolledToBottom, setHasScrolledToBottom] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const threshold = 50;
    const isAtBottom =
      el.scrollHeight - el.scrollTop - el.clientHeight < threshold;
    if (isAtBottom) {
      setHasScrolledToBottom(true);
    }
  }, []);

  const scrollToBottom = () => {
    scrollRef.current?.scrollTo({
      top: scrollRef.current.scrollHeight,
      behavior: "smooth",
    });
  };

  const handleAgree = () => {
    onAgree();
    onOpenChange(false);
    setHasScrolledToBottom(false);
  };

  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      setHasScrolledToBottom(false);
    }
    onOpenChange(newOpen);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        showCloseButton={false}
        className="sm:max-w-2xl max-h-[90vh] flex flex-col bg-zinc-950 border-white/10"
      >
        <DialogHeader>
          <DialogTitle className="text-xl font-bold flex items-center gap-2">
            <AlertTriangle className="h-5 w-5 text-amber-400" />
            {t("legal.title")}
          </DialogTitle>
          <DialogDescription>
            {t("legal.description")}
          </DialogDescription>
        </DialogHeader>

        {/* Scrollable content */}
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className="flex-1 overflow-y-auto pr-2 space-y-6 text-sm text-zinc-300 leading-relaxed max-h-[55vh] scrollbar-thin scrollbar-thumb-white/10"
        >
          {/* Section 1: Risk Warning */}
          <section>
            <h3 className="flex items-center gap-2 text-base font-semibold text-amber-400 mb-3 sticky top-0 bg-zinc-950 py-2">
              <AlertTriangle className="h-4 w-4" />
              {t("legal.riskTitle")}
            </h3>
            <div className="space-y-2 pl-1">
              <p><strong>1.1</strong>{" "}{t("legal.risk1")}</p>
              <p><strong>1.2</strong>{" "}{t("legal.risk2")}</p>
              <p><strong>1.3</strong>{" "}{t("legal.risk3")}</p>
              <p><strong>1.4</strong>{" "}{t("legal.risk4")}</p>
              <p><strong>1.5</strong>{" "}{t("legal.risk5")}</p>
              <p><strong>1.6</strong>{" "}{t("legal.risk6")}</p>
            </div>
          </section>

          {/* Section 2: Terms of Service */}
          <section>
            <h3 className="flex items-center gap-2 text-base font-semibold text-blue-400 mb-3 sticky top-0 bg-zinc-950 py-2">
              <FileText className="h-4 w-4" />
              {t("legal.termsTitle")}
            </h3>
            <div className="space-y-2 pl-1">
              <p><strong>{t("legal.terms1Label")}</strong>{" "}{t("legal.terms1Text")}</p>
              <p><strong>{t("legal.terms2Label")}</strong>{" "}{t("legal.terms2Text")}</p>
              <p><strong>{t("legal.terms3Label")}</strong>{" "}{t("legal.terms3Text")}</p>
              <p><strong>{t("legal.terms4Label")}</strong>{" "}{t("legal.terms4Text")}</p>
              <p><strong>{t("legal.terms5Label")}</strong>{" "}{t("legal.terms5Text")}</p>
              <p><strong>{t("legal.terms6Label")}</strong>{" "}{t("legal.terms6Text")}</p>
              <p><strong>{t("legal.terms7Label")}</strong>{" "}{t("legal.terms7Text")}</p>
              <p><strong>{t("legal.terms8Label")}</strong>{" "}{t("legal.terms8Text")}</p>
            </div>
          </section>

          {/* Section 3: Privacy Policy */}
          <section>
            <h3 className="flex items-center gap-2 text-base font-semibold text-emerald-400 mb-3 sticky top-0 bg-zinc-950 py-2">
              <Shield className="h-4 w-4" />
              {t("legal.privacyTitle")}
            </h3>
            <div className="space-y-2 pl-1">
              <p><strong>{t("legal.privacy1Label")}</strong>{" "}{t("legal.privacy1Text")}</p>
              <p><strong>{t("legal.privacy2Label")}</strong>{" "}{t("legal.privacy2Text")}</p>
              <p><strong>{t("legal.privacy3Label")}</strong>{" "}{t("legal.privacy3Text")}</p>
              <p><strong>{t("legal.privacy4Label")}</strong>{" "}{t("legal.privacy4Text")}</p>
              <p><strong>{t("legal.privacy5Label")}</strong>{" "}{t("legal.privacy5Text")}</p>
              <p><strong>{t("legal.privacy6Label")}</strong>{" "}{t("legal.privacy6Text")}</p>
            </div>
          </section>

          {/* Final statement */}
          <div className="border-t border-white/10 pt-4 pb-2">
            <p className="text-zinc-400 text-xs">
              {t("legal.lastUpdated")}
            </p>
            <p className="text-zinc-400 text-xs mt-1">
              {t("legal.contactInfo")}
            </p>
          </div>
        </div>

        {/* Footer */}
        <div className="flex flex-col gap-3 pt-2 border-t border-white/10">
          {!hasScrolledToBottom && (
            <button
              onClick={scrollToBottom}
              className="flex items-center justify-center gap-1 text-xs text-zinc-500 hover:text-zinc-300 transition-colors"
            >
              <ChevronDown className="h-3 w-3 animate-bounce" />
              {t("legal.scrollHint")}
            </button>
          )}
          <div className="flex gap-3">
            <Button
              variant="outline"
              className="flex-1 border-white/10 hover:bg-white/5"
              onClick={() => handleOpenChange(false)}
            >
              {t("legal.disagree")}
            </Button>
            <Button
              className="flex-1 bg-emerald-600 hover:bg-emerald-500 text-white font-medium"
              disabled={!hasScrolledToBottom}
              onClick={handleAgree}
            >
              {hasScrolledToBottom
                ? t("legal.agreeAll")
                : t("legal.readFirst")}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
