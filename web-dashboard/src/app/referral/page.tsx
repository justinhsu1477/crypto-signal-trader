"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { useT } from "@/lib/i18n/i18n-context";
import { getReferralStatus, submitReferralUid } from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
import type { ReferralStatusResponse, ReferralStatusEnum } from "@/types";
import {
  UserPlus,
  FileText,
  Clock,
  CheckCircle2,
  Copy,
  Check,
  ExternalLink,
  Loader2,
  AlertCircle,
} from "lucide-react";

// ─── Step indicator helpers ───

const STEPS = [
  { key: "register", icon: UserPlus, labelKey: "referral.stepRegister", descKey: "referral.stepRegisterDesc" },
  { key: "submit", icon: FileText, labelKey: "referral.stepSubmitUid", descKey: "referral.stepSubmitUidDesc" },
  { key: "wait", icon: Clock, labelKey: "referral.stepWait", descKey: "referral.stepWaitDesc" },
  { key: "complete", icon: CheckCircle2, labelKey: "referral.stepComplete", descKey: "referral.stepCompleteDesc" },
] as const;

function getActiveStepIndex(status: ReferralStatusEnum): number {
  switch (status) {
    case "NOT_STARTED":
      return 1; // step 2: submit UID (assume already registered)
    case "PENDING":
      return 2; // step 3: waiting
    case "VERIFIED":
      return 4; // all done
  }
}

// ─── Status badge ───

function StatusBadge({ status, t }: { status: ReferralStatusEnum; t: (key: string) => string }) {
  switch (status) {
    case "VERIFIED":
      return (
        <Badge className="bg-emerald-500/15 text-emerald-500 border-emerald-500/25">
          {t("referral.statusVerified")}
        </Badge>
      );
    case "PENDING":
      return (
        <Badge className="bg-yellow-500/15 text-yellow-500 border-yellow-500/25">
          {t("referral.statusPending")}
        </Badge>
      );
    default:
      return (
        <Badge variant="secondary">{t("referral.statusNotStarted")}</Badge>
      );
  }
}

// ─── Main page ───

export default function ReferralPage() {
  const { t } = useT();

  // Data state
  const [data, setData] = useState<ReferralStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // UID form state
  const [uidInput, setUidInput] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);

  // Copy state
  const [linkCopied, setLinkCopied] = useState(false);
  const [codeCopied, setCodeCopied] = useState(false);

  // ─── Fetch status ───
  useEffect(() => {
    let cancelled = false;
    async function fetchStatus() {
      setLoading(true);
      setError(null);
      try {
        const result = await getReferralStatus();
        if (!cancelled) setData(result);
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error ? err.message : t("common.loadFailed")
          );
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    fetchStatus();
    return () => {
      cancelled = true;
    };
  }, [t]);

  // ─── Submit UID ───
  async function handleSubmitUid() {
    if (!uidInput.trim()) {
      setMessage({ type: "error", text: t("referral.uidRequired") });
      return;
    }
    setSubmitting(true);
    setMessage(null);
    try {
      const result = await submitReferralUid({ exchangeUid: uidInput.trim() });
      setData(result);
      setMessage({ type: "success", text: t("referral.submitSuccess") });
    } catch (err) {
      let errorText = t("common.saveFailed");
      if (err instanceof Error) {
        try {
          const parsed = JSON.parse(err.message);
          errorText = parsed.error || parsed.message || err.message;
        } catch {
          errorText = err.message;
        }
      }
      setMessage({ type: "error", text: errorText });
    } finally {
      setSubmitting(false);
    }
  }

  // ─── Copy helper ───
  function handleCopy(text: string, type: "link" | "code") {
    navigator.clipboard.writeText(text);
    if (type === "link") {
      setLinkCopied(true);
      setTimeout(() => setLinkCopied(false), 2000);
    } else {
      setCodeCopied(true);
      setTimeout(() => setCodeCopied(false), 2000);
    }
  }

  // ─── Loading ───
  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  // ─── Error ───
  if (error) {
    return <div className="text-red-500 py-4">{error}</div>;
  }

  if (!data) {
    return <div className="text-red-500">{t("common.cannotLoad")}</div>;
  }

  const activeStep = getActiveStepIndex(data.status);

  return (
    <div className="space-y-6">
      {/* ─── Title ─── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{t("referral.title")}</h1>
          <p className="text-sm text-muted-foreground mt-1">
            {t("referral.subtitle")}
          </p>
        </div>
        <StatusBadge status={data.status} t={t} />
      </div>

      {/* ─── Step Indicator ─── */}
      <Card>
        <CardContent className="py-6">
          {/* Desktop: horizontal */}
          <div className="hidden md:flex items-start justify-between gap-2">
            {STEPS.map((step, idx) => {
              const Icon = step.icon;
              const stepNum = idx + 1;
              const isComplete = stepNum < activeStep;
              const isCurrent = stepNum === activeStep;
              return (
                <div key={step.key} className="flex-1 flex flex-col items-center text-center gap-2 relative">
                  {/* Connector line */}
                  {idx > 0 && (
                    <div
                      className={`absolute top-5 right-1/2 w-full h-[2px] -translate-y-1/2 ${
                        isComplete ? "bg-emerald-500/40" : "bg-border"
                      }`}
                      style={{ left: "-50%", right: "50%" }}
                    />
                  )}
                  {/* Icon circle */}
                  <div
                    className={`relative z-10 flex items-center justify-center w-10 h-10 rounded-full border-2 ${
                      isComplete
                        ? "bg-emerald-500/15 text-emerald-500 border-emerald-500/25"
                        : isCurrent
                          ? "bg-yellow-500/15 text-yellow-500 border-yellow-500/25"
                          : "bg-muted text-muted-foreground border-border"
                    }`}
                  >
                    {isComplete ? (
                      <Check className="h-5 w-5" />
                    ) : (
                      <Icon className="h-5 w-5" />
                    )}
                  </div>
                  <div>
                    <p className={`text-sm font-medium ${
                      isComplete ? "text-emerald-500" : isCurrent ? "text-yellow-500" : "text-muted-foreground"
                    }`}>
                      {t(step.labelKey)}
                    </p>
                    <p className="text-xs text-muted-foreground mt-0.5 hidden lg:block">
                      {t(step.descKey)}
                    </p>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Mobile: vertical */}
          <div className="md:hidden space-y-4">
            {STEPS.map((step, idx) => {
              const Icon = step.icon;
              const stepNum = idx + 1;
              const isComplete = stepNum < activeStep;
              const isCurrent = stepNum === activeStep;
              return (
                <div key={step.key} className="flex items-start gap-3">
                  <div
                    className={`flex items-center justify-center w-8 h-8 rounded-full border-2 shrink-0 ${
                      isComplete
                        ? "bg-emerald-500/15 text-emerald-500 border-emerald-500/25"
                        : isCurrent
                          ? "bg-yellow-500/15 text-yellow-500 border-yellow-500/25"
                          : "bg-muted text-muted-foreground border-border"
                    }`}
                  >
                    {isComplete ? (
                      <Check className="h-4 w-4" />
                    ) : (
                      <Icon className="h-4 w-4" />
                    )}
                  </div>
                  <div>
                    <p className={`text-sm font-medium ${
                      isComplete ? "text-emerald-500" : isCurrent ? "text-yellow-500" : "text-muted-foreground"
                    }`}>
                      {t(step.labelKey)}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {t(step.descKey)}
                    </p>
                  </div>
                </div>
              );
            })}
          </div>
        </CardContent>
      </Card>

      {/* ─── Referral Link Card ─── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("referral.referralLinkLabel")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* Link row */}
          <div className="flex items-center gap-2">
            <Input
              readOnly
              value={data.referralLink}
              className="flex-1 text-sm bg-muted/30"
            />
            <Button
              variant="outline"
              size="sm"
              onClick={() => handleCopy(data.referralLink, "link")}
              className="shrink-0"
            >
              {linkCopied ? (
                <><Check className="h-4 w-4 mr-1 text-emerald-500" />{t("referral.copied")}</>
              ) : (
                <><Copy className="h-4 w-4 mr-1" />{t("referral.copyLink")}</>
              )}
            </Button>
          </div>

          <Separator />

          {/* Code + Open Binance */}
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div className="flex items-center gap-2">
              <span className="text-sm text-muted-foreground">{t("referral.referralCodeLabel")}:</span>
              <code className="px-2 py-1 bg-muted/50 rounded text-sm font-mono">
                {data.referralCode}
              </code>
              <Button
                variant="ghost"
                size="icon-xs"
                onClick={() => handleCopy(data.referralCode, "code")}
              >
                {codeCopied ? (
                  <Check className="h-3.5 w-3.5 text-emerald-500" />
                ) : (
                  <Copy className="h-3.5 w-3.5" />
                )}
              </Button>
            </div>
            <Button
              variant="outline"
              size="sm"
              asChild
            >
              <a href={data.referralLink} target="_blank" rel="noopener noreferrer">
                <ExternalLink className="h-4 w-4 mr-1" />
                {t("referral.openBinance")}
              </a>
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* ─── Status-dependent section ─── */}

      {/* NOT_STARTED: UID form */}
      {data.status === "NOT_STARTED" && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t("referral.stepSubmitUid")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="uid">{t("referral.uidLabel")}</Label>
              <Input
                id="uid"
                placeholder={t("referral.uidPlaceholder")}
                value={uidInput}
                onChange={(e) => setUidInput(e.target.value)}
                className="max-w-sm"
              />
              <p className="text-xs text-muted-foreground">
                {t("referral.uidHelp")}
              </p>
            </div>

            <Button
              onClick={handleSubmitUid}
              disabled={submitting}
            >
              {submitting ? (
                <><Loader2 className="h-4 w-4 mr-1 animate-spin" />{t("referral.submitting")}</>
              ) : (
                t("referral.submitUid")
              )}
            </Button>

            {message && (
              <p
                className={`text-sm ${
                  message.type === "success"
                    ? "text-emerald-500"
                    : "text-red-500"
                }`}
              >
                {message.text}
              </p>
            )}
          </CardContent>
        </Card>
      )}

      {/* PENDING: waiting card */}
      {data.status === "PENDING" && (
        <Card>
          <CardContent className="pt-6">
            <div className="p-4 bg-yellow-950/30 border border-yellow-900 rounded-lg space-y-3">
              <div className="flex items-center gap-2">
                <Clock className="h-5 w-5 text-yellow-500 shrink-0" />
                <p className="text-sm font-medium text-yellow-300">
                  {t("referral.pendingTitle")}
                </p>
              </div>
              <p className="text-sm text-yellow-300/80">
                {t("referral.pendingMessage")}
              </p>
              <div className="flex items-center gap-2 text-sm">
                <span className="text-muted-foreground">{t("referral.submittedUid")}:</span>
                <code className="px-2 py-0.5 bg-muted/50 rounded font-mono">
                  {data.exchangeUid}
                </code>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* VERIFIED: success card */}
      {data.status === "VERIFIED" && (
        <Card>
          <CardContent className="pt-6">
            <div className="p-4 bg-emerald-950/30 border border-emerald-900 rounded-lg space-y-3">
              <div className="flex items-center gap-2">
                <CheckCircle2 className="h-5 w-5 text-emerald-500 shrink-0" />
                <p className="text-sm font-medium text-emerald-300">
                  {t("referral.verifiedTitle")}
                </p>
              </div>
              <p className="text-sm text-emerald-300/80">
                {t("referral.verifiedMessage")}
              </p>
              {data.verifiedAt && (
                <p className="text-xs text-muted-foreground">
                  {t("referral.verifiedAt", { time: formatDateTime(data.verifiedAt) })}
                </p>
              )}
              <Button variant="outline" size="sm" asChild>
                <Link href="/">
                  {t("referral.goToDashboard")}
                </Link>
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
