"use client";

import React, { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle, RefreshCw } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";

/* ------------------------------------------------------------------ */
/*  ErrorBoundary — React class component（必須用 class 才能攔截 render 錯誤）*/
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
/*  SectionErrorFallback — 區塊級 fallback UI                         */
/* ------------------------------------------------------------------ */

interface SectionErrorFallbackProps {
  error?: Error | null;
  onRetry?: () => void;
}

export function SectionErrorFallback({ error, onRetry }: SectionErrorFallbackProps) {
  return (
    <Card className="border-yellow-500/30">
      <CardContent className="flex flex-col items-center justify-center py-8 text-center">
        <AlertTriangle className="mb-3 h-8 w-8 text-yellow-500" />
        <p className="mb-1 text-sm font-medium text-foreground">
          此區塊發生錯誤
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
            重試
          </button>
        )}
      </CardContent>
    </Card>
  );
}

/* ------------------------------------------------------------------ */
/*  PageErrorFallback — 全頁級 fallback（不依賴 i18n context）          */
/* ------------------------------------------------------------------ */

export function PageErrorFallback() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background p-4 text-center">
      <AlertTriangle className="mb-4 h-12 w-12 text-yellow-500" />
      <h1 className="mb-2 text-xl font-bold text-foreground">
        頁面發生錯誤
      </h1>
      <p className="mb-4 text-sm text-muted-foreground">
        Something went wrong. Please try refreshing the page.
      </p>
      <button
        onClick={() => window.location.reload()}
        className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
      >
        <RefreshCw className="h-4 w-4" />
        重新整理
      </button>
    </div>
  );
}
