"use client";

import React, { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle, RefreshCw } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";

/* ------------------------------------------------------------------ */
/*  Locale detection helper (no React context dependency)              */
/* ------------------------------------------------------------------ */

type FallbackLocale = "en" | "zh-TW" | "zh-CN";

function detectLocale(): FallbackLocale {
  if (typeof navigator === "undefined") return "en";
  const lang = navigator.language;
  if (lang.startsWith("zh")) {
    return lang.includes("CN") || lang.includes("Hans") ? "zh-CN" : "zh-TW";
  }
  return "en";
}

const fallbackLabels: Record<FallbackLocale, {
  sectionError: string;
  retry: string;
  pageError: string;
  pageErrorDesc: string;
  refresh: string;
}> = {
  en: {
    sectionError: "Something went wrong in this section",
    retry: "Retry",
    pageError: "Something went wrong",
    pageErrorDesc: "An unexpected error occurred. Please try refreshing the page.",
    refresh: "Refresh Page",
  },
  "zh-TW": {
    sectionError: "此區塊發生錯誤",
    retry: "重試",
    pageError: "頁面發生錯誤",
    pageErrorDesc: "發生未預期的錯誤，請嘗試重新整理頁面。",
    refresh: "重新整理",
  },
  "zh-CN": {
    sectionError: "此区块发生错误",
    retry: "重试",
    pageError: "页面发生错误",
    pageErrorDesc: "发生未预期的错误，请尝试刷新页面。",
    refresh: "刷新页面",
  },
};

function getLabels() {
  return fallbackLabels[detectLocale()];
}

/* ------------------------------------------------------------------ */
/*  ErrorBoundary — React class component                              */
/* ------------------------------------------------------------------ */

interface ErrorBoundaryProps {
  children: ReactNode;
  fallback?: ReactNode;
  onReset?: () => void;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error("[ErrorBoundary]", error, errorInfo);
  }

  resetErrorBoundary = () => {
    this.props.onReset?.();
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }
      return (
        <SectionErrorFallback
          error={this.state.error}
          onRetry={this.resetErrorBoundary}
        />
      );
    }
    return this.props.children;
  }
}

/* ------------------------------------------------------------------ */
/*  SectionErrorFallback — section-level fallback UI                   */
/* ------------------------------------------------------------------ */

interface SectionErrorFallbackProps {
  error?: Error | null;
  onRetry?: () => void;
}

export function SectionErrorFallback({ error, onRetry }: SectionErrorFallbackProps) {
  const labels = getLabels();

  return (
    <Card className="border-yellow-500/30">
      <CardContent className="flex flex-col items-center justify-center py-8 text-center">
        <AlertTriangle className="mb-3 h-8 w-8 text-yellow-500" />
        <p className="mb-1 text-sm font-medium text-foreground">
          {labels.sectionError}
        </p>
        {error?.message && (
          <p className="mb-3 text-xs text-muted-foreground">{error.message}</p>
        )}
        {onRetry && (
          <button
            onClick={onRetry}
            className="inline-flex items-center gap-1.5 rounded-md bg-muted px-3 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-muted/80"
          >
            <RefreshCw className="h-3 w-3" />
            {labels.retry}
          </button>
        )}
      </CardContent>
    </Card>
  );
}

/* ------------------------------------------------------------------ */
/*  PageErrorFallback — full-page fallback (no i18n context)           */
/* ------------------------------------------------------------------ */

export function PageErrorFallback() {
  const labels = getLabels();

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background p-4 text-center">
      <AlertTriangle className="mb-4 h-12 w-12 text-yellow-500" />
      <h1 className="mb-2 text-xl font-bold text-foreground">
        {labels.pageError}
      </h1>
      <p className="mb-4 text-sm text-muted-foreground">
        {labels.pageErrorDesc}
      </p>
      <button
        onClick={() => window.location.reload()}
        className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
      >
        <RefreshCw className="h-4 w-4" />
        {labels.refresh}
      </button>
    </div>
  );
}
