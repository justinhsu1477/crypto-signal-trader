"use client";

import { useState, useMemo, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import Image from "next/image";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Loader2 } from "lucide-react";
import { LegalDisclaimerDialog } from "@/components/auth/legal-disclaimer-dialog";

/** LINE brand color */
const LINE_GREEN = "#06C755";

export default function RegisterPage() {
  const router = useRouter();
  const { register } = useAuth();
  const { t } = useT();

  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [agreedToTerms, setAgreedToTerms] = useState(false);
  const [showLegalDialog, setShowLegalDialog] = useState(false);

  function handleLineRegister() {
    window.location.href = "/api/auth/oauth/line";
  }

  // Password strength checks (only 8+ chars and mixed case)
  const passwordChecks = useMemo(() => [
    { key: "min", passed: password.length >= 8, label: t("register.passwordMinChars") },
    { key: "mixed", passed: /[A-Z]/.test(password) && /[a-z]/.test(password), label: t("register.passwordMixedCase") },
  ], [password, t]);
  const isPasswordValid = passwordChecks.every((check) => check.passed);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError("");
    setIsLoading(true);

    try {
      await register({ name, email, password, termsAccepted: agreedToTerms });
      router.push(`/verify-email?email=${encodeURIComponent(email)}`);
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError(t("register.registerFailed"));
      }
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <div className="rounded-[30px] bg-white p-8 shadow-[0_2px_12px_rgba(0,0,0,0.08)]">
      {/* Logo + Brand */}
      <div className="flex flex-col items-center mb-6">
        <div className="relative mb-4">
          <Image
            src="/logo.jpg"
            alt="HookFi"
            width={56}
            height={56}
            className="relative rounded-2xl shadow-sm"
          />
        </div>
        <h1 className="text-xl font-extrabold tracking-tight text-black">
          HookFi
        </h1>
      </div>

      <div className="text-center mb-6">
        <h2 className="text-2xl font-bold text-black">{t("register.createAccount")}</h2>
        <p className="text-sm text-gray-500 mt-1">
          {t("register.subtitle")}
        </p>
      </div>

      <div className="space-y-4">
        {/* LINE Register / Login Button */}
        <Button
          type="button"
          onClick={handleLineRegister}
          className="w-full h-11 font-bold rounded-full transition-all duration-200 hover:shadow-lg text-white"
          style={{ backgroundColor: LINE_GREEN }}
        >
          <svg viewBox="0 0 24 24" className="mr-2 h-5 w-5 fill-current" aria-hidden="true">
            <path d="M19.365 9.863c.349 0 .63.285.63.631 0 .345-.281.63-.63.63H17.61v1.125h1.755c.349 0 .63.283.63.63 0 .344-.281.629-.63.629h-2.386c-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63h2.386c.346 0 .627.285.627.63 0 .349-.281.63-.63.63H17.61v1.125h1.755zm-3.855 3.016c0 .27-.174.51-.432.596-.064.021-.133.031-.199.031-.211 0-.391-.09-.51-.25l-2.443-3.317v2.94c0 .344-.279.629-.631.629-.346 0-.626-.285-.626-.629V8.108c0-.27.173-.51.43-.595.06-.023.136-.033.194-.033.195 0 .375.104.495.254l2.462 3.33V8.108c0-.345.282-.63.63-.63.345 0 .63.285.63.63v4.771zm-5.741 0c0 .344-.282.629-.631.629-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63.346 0 .628.285.628.63v4.771zm-2.466.629H4.917c-.345 0-.63-.285-.63-.629V8.108c0-.345.285-.63.63-.63.348 0 .63.285.63.63v4.141h1.756c.348 0 .629.283.629.63 0 .344-.282.629-.629.629M24 10.314C24 4.943 18.615.572 12 .572S0 4.943 0 10.314c0 4.811 4.27 8.842 10.035 9.608.391.082.923.258 1.058.59.12.301.079.766.038 1.08l-.164 1.02c-.045.301-.24 1.186 1.049.645 1.291-.539 6.916-4.078 9.436-6.975C23.176 14.393 24 12.458 24 10.314" />
          </svg>
          {t("register.lineRegister")}
        </Button>

        {/* Divider */}
        <div className="relative">
          <div className="absolute inset-0 flex items-center">
            <div className="w-full border-t border-gray-200" />
          </div>
          <div className="relative flex justify-center text-xs">
            <span className="bg-white px-3 text-gray-400">{t("register.orDivider")}</span>
          </div>
        </div>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4 mt-4">
        {error && (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-2.5 text-sm text-red-600">
            {error}
          </div>
        )}

        <div className="space-y-2">
          <Label htmlFor="name" className="text-sm text-gray-600">
            {t("register.name")}
          </Label>
          <Input
            id="name"
            type="text"
            placeholder={t("register.namePlaceholder")}
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            autoComplete="name"
            className="h-11 bg-gray-50 border-gray-200 text-black focus:border-black focus:ring-black/10 placeholder:text-gray-400"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="email" className="text-sm text-gray-600">
            {t("login.email")}
          </Label>
          <Input
            id="email"
            type="email"
            placeholder="you@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoComplete="email"
            className="h-11 bg-gray-50 border-gray-200 text-black focus:border-black focus:ring-black/10 placeholder:text-gray-400"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="password" className="text-sm text-gray-600">
            {t("login.password")}
          </Label>
          <Input
            id="password"
            type="password"
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoComplete="new-password"
            className="h-11 bg-gray-50 border-gray-200 text-black focus:border-black focus:ring-black/10 placeholder:text-gray-400"
          />
        </div>

        {/* Password requirements */}
        {password.length > 0 && (
          <div className="space-y-1.5 rounded-lg bg-gray-50 border border-gray-200 px-3 py-2.5">
            <p className="text-xs font-medium text-gray-600">{t("register.passwordRequirements")}</p>
            {passwordChecks.map((check) => (
              <div key={check.key} className="flex items-center gap-2 text-xs">
                <span className={check.passed ? "text-green-600" : "text-gray-400"}>
                  {check.passed ? "✓" : "○"}
                </span>
                <span className={check.passed ? "text-green-600" : "text-gray-400"}>
                  {check.label}
                </span>
              </div>
            ))}
          </div>
        )}

        {/* Terms agreement checkbox */}
        <div className="flex items-start gap-2 pt-1">
          <input
            type="checkbox"
            id="agree-terms"
            checked={agreedToTerms}
            onChange={(e) => {
              if (!agreedToTerms) {
                e.preventDefault();
                setShowLegalDialog(true);
              } else {
                setAgreedToTerms(false);
              }
            }}
            className="mt-1 h-4 w-4 rounded border-gray-300 bg-white text-black focus:ring-black/20 cursor-pointer accent-black"
          />
          <label htmlFor="agree-terms" className="text-xs text-gray-500 leading-relaxed">
            {t("register.agreeTermsPrefix")}
            <button
              type="button"
              onClick={() => setShowLegalDialog(true)}
              className="text-black hover:text-gray-700 underline underline-offset-2 transition-colors font-medium"
            >
              {t("register.termsAndConditions")}
            </button>
          </label>
        </div>

        <Button
          type="submit"
          className="w-full h-11 bg-black hover:bg-gray-800 text-white font-bold rounded-full transition-all duration-200 hover:shadow-lg disabled:opacity-40 disabled:cursor-not-allowed"
          disabled={isLoading || !agreedToTerms || !isPasswordValid}
        >
          {isLoading ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              {t("register.registering")}
            </>
          ) : (
            t("register.registerButton")
          )}
        </Button>

        <LegalDisclaimerDialog
          open={showLegalDialog}
          onOpenChange={setShowLegalDialog}
          onAgree={() => setAgreedToTerms(true)}
        />

        <p className="text-center text-sm text-gray-500 pt-2">
          {t("register.hasAccount")}
          <Link
            href="/login"
            className="font-medium text-black hover:text-gray-700 transition-colors underline underline-offset-2"
          >
            {t("register.backToLogin")}
          </Link>
        </p>
      </form>
    </div>
  );
}
