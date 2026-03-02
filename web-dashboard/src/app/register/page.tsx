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

      <form onSubmit={handleSubmit} className="space-y-4">
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
