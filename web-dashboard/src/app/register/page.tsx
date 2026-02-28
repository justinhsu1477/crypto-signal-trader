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

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError("");
    setIsLoading(true);

    try {
      await register({ name, email, password });
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
        <h2 className="text-2xl font-bold">{t("register.createAccount")}</h2>
        <p className="text-sm text-muted-foreground mt-1">
          {t("register.subtitle")}
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        {error && (
          <div className="rounded-lg bg-red-500/10 border border-red-500/20 px-4 py-2.5 text-sm text-red-400">
            {error}
          </div>
        )}

        <div className="space-y-2">
          <Label htmlFor="name" className="text-sm text-muted-foreground">
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
            className="h-11 bg-white/5 border-white/10 focus:border-emerald-500/50 focus:ring-emerald-500/20 placeholder:text-white/20"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="email" className="text-sm text-muted-foreground">
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
            className="h-11 bg-white/5 border-white/10 focus:border-emerald-500/50 focus:ring-emerald-500/20 placeholder:text-white/20"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="password" className="text-sm text-muted-foreground">
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
            className="h-11 bg-white/5 border-white/10 focus:border-emerald-500/50 focus:ring-emerald-500/20 placeholder:text-white/20"
          />
        </div>

        {/* Password requirements */}
        {password.length > 0 && (
          <div className="space-y-1.5 rounded-lg bg-white/[0.02] border border-white/5 px-3 py-2.5">
            <p className="text-xs font-medium text-muted-foreground">{t("register.passwordRequirements")}</p>
            {passwordChecks.map((check) => (
              <div key={check.key} className="flex items-center gap-2 text-xs">
                <span className={check.passed ? "text-emerald-400" : "text-zinc-500"}>
                  {check.passed ? "✓" : "○"}
                </span>
                <span className={check.passed ? "text-emerald-400" : "text-zinc-500"}>
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
            className="mt-1 h-4 w-4 rounded border-white/20 bg-white/5 text-emerald-500 focus:ring-emerald-500/20 cursor-pointer accent-emerald-500"
          />
          <label htmlFor="agree-terms" className="text-xs text-zinc-400 leading-relaxed">
            {t("register.agreeTermsPrefix")}
            <button
              type="button"
              onClick={() => setShowLegalDialog(true)}
              className="text-emerald-400 hover:text-emerald-300 underline underline-offset-2 transition-colors"
            >
              {t("register.termsAndConditions")}
            </button>
          </label>
        </div>

        <Button
          type="submit"
          className="w-full h-11 bg-emerald-600 hover:bg-emerald-500 text-white font-medium transition-all duration-200 hover:shadow-lg hover:shadow-emerald-500/20 disabled:opacity-40 disabled:cursor-not-allowed"
          disabled={isLoading || !agreedToTerms}
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

        <p className="text-center text-sm text-muted-foreground pt-2">
          {t("register.hasAccount")}
          <Link
            href="/login"
            className="font-medium text-emerald-400 hover:text-emerald-300 transition-colors"
          >
            {t("register.backToLogin")}
          </Link>
        </p>
      </form>
    </div>
  );
}
