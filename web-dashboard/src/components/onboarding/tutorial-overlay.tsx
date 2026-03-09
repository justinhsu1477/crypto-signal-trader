"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { useT } from "@/lib/i18n/i18n-context";

const STORAGE_KEY = "tutorial-completed";

interface TutorialStep {
  target: string; // data-tutorial-step attribute value
  titleKey: string;
  descKey: string;
}

const STEPS: TutorialStep[] = [
  { target: "", titleKey: "tutorial.welcomeTitle", descKey: "tutorial.welcomeDesc" },
  { target: "kpi-cards", titleKey: "tutorial.kpiTitle", descKey: "tutorial.kpiDesc" },
  { target: "risk-budget", titleKey: "tutorial.riskTitle", descKey: "tutorial.riskDesc" },
  { target: "positions-table", titleKey: "tutorial.positionsTitle", descKey: "tutorial.positionsDesc" },
  { target: "sidebar-nav", titleKey: "tutorial.sidebarTitle", descKey: "tutorial.sidebarDesc" },
];

export function TutorialOverlay() {
  const { t } = useT();
  const [currentStep, setCurrentStep] = useState(0);
  const [visible, setVisible] = useState(() => {
    if (typeof window !== "undefined") {
      return !localStorage.getItem(STORAGE_KEY);
    }
    return false;
  });
  const [spotlightRect, setSpotlightRect] = useState<DOMRect | null>(null);
  const observerRef = useRef<ResizeObserver | null>(null);

  const updateSpotlight = useCallback(() => {
    const step = STEPS[currentStep];
    if (!step?.target) {
      setSpotlightRect(null);
      return;
    }
    const el = document.querySelector(`[data-tutorial-step="${step.target}"]`);
    if (el) {
      setSpotlightRect(el.getBoundingClientRect());
    } else {
      setSpotlightRect(null);
    }
  }, [currentStep]);

  useEffect(() => {
    if (!visible) return;
    // Defer to avoid synchronous setState in effect body
    const rafId = requestAnimationFrame(() => updateSpotlight());

    // Observe resize
    const step = STEPS[currentStep];
    if (step?.target) {
      const el = document.querySelector(`[data-tutorial-step="${step.target}"]`);
      if (el) {
        observerRef.current = new ResizeObserver(() => updateSpotlight());
        observerRef.current.observe(el);
      }
    }

    const handleResize = () => updateSpotlight();
    window.addEventListener("resize", handleResize);

    return () => {
      cancelAnimationFrame(rafId);
      window.removeEventListener("resize", handleResize);
      observerRef.current?.disconnect();
    };
  }, [visible, currentStep, updateSpotlight]);

  const completeTutorial = useCallback(() => {
    localStorage.setItem(STORAGE_KEY, "true");
    setVisible(false);
  }, []);

  // ESC to skip
  useEffect(() => {
    if (!visible) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        completeTutorial();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  });

  const next = () => {
    if (currentStep >= STEPS.length - 1) {
      completeTutorial();
    } else {
      setCurrentStep((s) => s + 1);
    }
  };

  const prev = () => {
    if (currentStep > 0) {
      setCurrentStep((s) => s - 1);
    }
  };

  if (!visible) return null;

  const step = STEPS[currentStep];
  const isLastStep = currentStep === STEPS.length - 1;

  // Calculate tooltip position
  const tooltipStyle: React.CSSProperties = {};
  if (spotlightRect) {
    tooltipStyle.position = "fixed";
    tooltipStyle.top = spotlightRect.bottom + 12;
    tooltipStyle.left = Math.max(16, spotlightRect.left);
    tooltipStyle.maxWidth = "360px";
    tooltipStyle.zIndex = 10002;

    // If tooltip would go off-screen bottom, show above
    if (spotlightRect.bottom + 200 > window.innerHeight) {
      tooltipStyle.top = Math.max(16, spotlightRect.top - 200);
    }
  } else {
    // Welcome step: centered
    tooltipStyle.position = "fixed";
    tooltipStyle.top = "50%";
    tooltipStyle.left = "50%";
    tooltipStyle.transform = "translate(-50%, -50%)";
    tooltipStyle.maxWidth = "400px";
    tooltipStyle.zIndex = 10002;
  }

  // Build clip-path to create spotlight effect
  const overlayStyle: React.CSSProperties = {
    position: "fixed",
    inset: 0,
    zIndex: 10000,
    backgroundColor: "rgba(0, 0, 0, 0.6)",
  };

  if (spotlightRect) {
    const pad = 8;
    const x = spotlightRect.left - pad;
    const y = spotlightRect.top - pad;
    const w = spotlightRect.width + pad * 2;
    const h = spotlightRect.height + pad * 2;
    const r = 8;

    overlayStyle.clipPath = `polygon(
      0% 0%, 0% 100%, 100% 100%, 100% 0%, 0% 0%,
      ${x}px ${y + r}px,
      ${x + r}px ${y}px,
      ${x + w - r}px ${y}px,
      ${x + w}px ${y + r}px,
      ${x + w}px ${y + h - r}px,
      ${x + w - r}px ${y + h}px,
      ${x + r}px ${y + h}px,
      ${x}px ${y + h - r}px,
      ${x}px ${y + r}px
    )`;
  }

  return (
    <>
      {/* Overlay with spotlight cutout */}
      <div style={overlayStyle} onClick={completeTutorial} />

      {/* Tooltip card */}
      <div style={tooltipStyle}>
        <Card className="shadow-xl border-primary/30">
          <CardContent className="p-4 space-y-3">
            <div>
              <h3 className="font-semibold text-base">{t(step.titleKey)}</h3>
              <p className="text-sm text-muted-foreground mt-1">{t(step.descKey)}</p>
            </div>

            <div className="flex items-center justify-between">
              <span className="text-xs text-muted-foreground">
                {t("tutorial.stepOf", { current: currentStep + 1, total: STEPS.length })}
              </span>
              <div className="flex gap-2">
                <Button variant="ghost" size="sm" onClick={completeTutorial}>
                  {t("tutorial.skip")}
                </Button>
                {currentStep > 0 && (
                  <Button variant="outline" size="sm" onClick={prev}>
                    {t("tutorial.prev")}
                  </Button>
                )}
                <Button size="sm" onClick={next}>
                  {isLastStep ? t("tutorial.finish") : t("tutorial.nextStep")}
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </>
  );
}
