"use client";

import { Suspense, useState, useRef, useEffect, type FormEvent, type KeyboardEvent, type ClipboardEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import Image from "next/image";
import { useT } from "@/lib/i18n/i18n-context";
import { verifyEmail, resendCode } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Loader2, Mail, ArrowLeft } from "lucide-react";

const CODE_LENGTH = 6;
const RESEND_COOLDOWN = 60; // seconds

function VerifyEmailForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { t } = useT();

  const email = searchParams.get("email") || "";

  const [digits, setDigits] = useState<string[]>(Array(CODE_LENGTH).fill(""));
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [isVerifying, setIsVerifying] = useState(false);
  const [isResending, setIsResending] = useState(false);
  const [countdown, setCountdown] = useState(RESEND_COOLDOWN);

  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);

  // Countdown timer for resend
  useEffect(() => {
    if (countdown <= 0) return;
    const timer = setInterval(() => {
      setCountdown((prev) => prev - 1);
    }, 1000);
    return () => clearInterval(timer);
  }, [countdown]);

  // Auto-focus first input on mount
  useEffect(() => {
    inputRefs.current[0]?.focus();
  }, []);

  // If no email, redirect back
  useEffect(() => {
    if (!email) {
      router.replace("/register");
    }
  }, [email, router]);

  function handleDigitChange(index: number, value: string) {
    if (!/^\d*$/.test(value)) return; // only digits

    const newDigits = [...digits];
    newDigits[index] = value.slice(-1); // take last char
    setDigits(newDigits);
    setError("");

    // Auto-advance to next input
    if (value && index < CODE_LENGTH - 1) {
      inputRefs.current[index + 1]?.focus();
    }

    // Auto-submit when all filled
    if (newDigits.every((d) => d) && newDigits.join("").length === CODE_LENGTH) {
      handleSubmitCode(newDigits.join(""));
    }
  }

  function handleKeyDown(index: number, e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Backspace" && !digits[index] && index > 0) {
      // Move to previous input on backspace
      inputRefs.current[index - 1]?.focus();
    }
  }

  function handlePaste(e: ClipboardEvent<HTMLInputElement>) {
    e.preventDefault();
    const pasted = e.clipboardData.getData("text").replace(/\D/g, "").slice(0, CODE_LENGTH);
    if (!pasted) return;

    const newDigits = [...digits];
    for (let i = 0; i < CODE_LENGTH; i++) {
      newDigits[i] = pasted[i] || "";
    }
    setDigits(newDigits);
    setError("");

    // Focus last filled or next empty
    const lastIndex = Math.min(pasted.length, CODE_LENGTH) - 1;
    inputRefs.current[lastIndex]?.focus();

    // Auto-submit if complete
    if (pasted.length === CODE_LENGTH) {
      handleSubmitCode(pasted);
    }
  }

  async function handleSubmitCode(code: string) {
    setError("");
    setIsVerifying(true);

    try {
      await verifyEmail({ email, code });
      router.push("/login?verified=true");
    } catch (err: unknown) {
      let errorText = t("verify.verifyFailed");
      if (err instanceof Error) {
        try {
          const parsed = JSON.parse(err.message);
          errorText = parsed.error || parsed.message || err.message;
        } catch {
          errorText = err.message;
        }
      }
      setError(errorText);
      // Clear digits for retry
      setDigits(Array(CODE_LENGTH).fill(""));
      inputRefs.current[0]?.focus();
    } finally {
      setIsVerifying(false);
    }
  }

  async function handleFormSubmit(e: FormEvent) {
    e.preventDefault();
    const code = digits.join("");
    if (code.length !== CODE_LENGTH) {
      setError(t("verify.enterCode"));
      return;
    }
    await handleSubmitCode(code);
  }

  async function handleResend() {
    if (countdown > 0 || isResending) return;
    setIsResending(true);
    setError("");
    setSuccess("");

    try {
      await resendCode({ email });
      setSuccess(t("verify.resendSuccess"));
      setCountdown(RESEND_COOLDOWN);
    } catch (err: unknown) {
      let errorText = t("verify.resendFailed");
      if (err instanceof Error) {
        try {
          const parsed = JSON.parse(err.message);
          errorText = parsed.error || parsed.message || err.message;
        } catch {
          errorText = err.message;
        }
      }
      setError(errorText);
    } finally {
      setIsResending(false);
    }
  }

  if (!email) return null;

  return (
    <div className="rounded-2xl border border-white/10 bg-white/[0.03] backdrop-blur-xl p-8 shadow-2xl shadow-black/20">
      {/* Logo + Brand */}
      <div className="flex flex-col items-center mb-6">
        <div className="relative mb-4">
          <div className="absolute inset-0 rounded-2xl bg-emerald-500/20 blur-xl" />
          <Image
            src="/logo.jpg"
            alt="HookFi"
            width={64}
            height={64}
            className="relative rounded-2xl shadow-lg shadow-black/30"
          />
        </div>
        <h1 className="text-xl font-bold tracking-tight bg-gradient-to-r from-emerald-400 to-blue-400 bg-clip-text text-transparent">
          HookFi
        </h1>
      </div>

      <div className="text-center mb-6">
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-500/10">
          <Mail className="h-6 w-6 text-emerald-400" />
        </div>
        <h2 className="text-2xl font-bold">{t("verify.title")}</h2>
        <p className="text-sm text-muted-foreground mt-2">
          {t("verify.codeSent", { email })}
        </p>
      </div>

      <form onSubmit={handleFormSubmit} className="space-y-6">
        {error && (
          <div className="rounded-lg bg-red-500/10 border border-red-500/20 px-4 py-2.5 text-sm text-red-400">
            {error}
          </div>
        )}

        {success && (
          <div className="rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-4 py-2.5 text-sm text-emerald-400">
            {success}
          </div>
        )}

        {/* OTP Input Grid */}
        <div className="flex justify-center gap-2 sm:gap-3">
          {digits.map((digit, index) => (
            <Input
              key={index}
              ref={(el) => { inputRefs.current[index] = el; }}
              type="text"
              inputMode="numeric"
              maxLength={1}
              value={digit}
              onChange={(e) => handleDigitChange(index, e.target.value)}
              onKeyDown={(e) => handleKeyDown(index, e)}
              onPaste={index === 0 ? handlePaste : undefined}
              disabled={isVerifying}
              className="h-14 w-12 sm:w-14 text-center text-2xl font-bold bg-white/5 border-white/10 focus:border-emerald-500/50 focus:ring-emerald-500/20"
              aria-label={`${t("verify.digit")} ${index + 1}`}
            />
          ))}
        </div>

        {/* Verify Button */}
        <Button
          type="submit"
          className="w-full h-11 bg-emerald-600 hover:bg-emerald-500 text-white font-medium transition-all duration-200 hover:shadow-lg hover:shadow-emerald-500/20"
          disabled={isVerifying || digits.join("").length !== CODE_LENGTH}
        >
          {isVerifying ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              {t("verify.verifying")}
            </>
          ) : (
            t("verify.verifyButton")
          )}
        </Button>

        {/* Resend */}
        <div className="text-center text-sm text-muted-foreground">
          <span>{t("verify.noCode")} </span>
          {countdown > 0 ? (
            <span className="text-white/40">
              {t("verify.resendIn", { seconds: countdown })}
            </span>
          ) : (
            <button
              type="button"
              onClick={handleResend}
              disabled={isResending}
              className="font-medium text-emerald-400 hover:text-emerald-300 transition-colors disabled:opacity-50"
            >
              {isResending ? t("verify.resending") : t("verify.resendCode")}
            </button>
          )}
        </div>

        {/* Back to Register */}
        <p className="text-center text-sm text-muted-foreground">
          <Link
            href="/register"
            className="inline-flex items-center gap-1 font-medium text-emerald-400 hover:text-emerald-300 transition-colors"
          >
            <ArrowLeft className="h-3 w-3" />
            {t("verify.backToRegister")}
          </Link>
        </p>
      </form>
    </div>
  );
}

export default function VerifyEmailPage() {
  return (
    <Suspense>
      <VerifyEmailForm />
    </Suspense>
  );
}
