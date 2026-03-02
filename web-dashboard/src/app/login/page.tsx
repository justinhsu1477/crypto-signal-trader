"use client";

import { Suspense, useState, useEffect, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import Image from "next/image";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Loader2, CheckCircle2 } from "lucide-react";

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { login } = useAuth();
  const { t } = useT();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [isLoading, setIsLoading] = useState(false);

  // 顯示提示訊息
  useEffect(() => {
    if (searchParams.get("verified") === "true") {
      setSuccess(t("login.emailVerifiedSuccess"));
    }
    if (searchParams.get("reset") === "true") {
      setSuccess(t("login.passwordResetSuccess"));
    }
  }, [searchParams, t]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError("");
    setSuccess("");
    setIsLoading(true);

    try {
      await login({ email, password });
      router.push("/");
    } catch (err: unknown) {
      if (err instanceof Error) {
        // 解析 EMAIL_NOT_VERIFIED 錯誤 → 導向驗證頁
        try {
          const parsed = JSON.parse(err.message);
          if (parsed.error === "EMAIL_NOT_VERIFIED") {
            const redirectEmail = parsed.email || email;
            router.push(`/verify-email?email=${encodeURIComponent(redirectEmail)}`);
            return;
          }
          // 將後端中文錯誤訊息映射到 i18n
          const backendError = parsed.error || parsed.message || err.message;
          const errorI18nMap: Record<string, string> = {
            "帳號或密碼錯誤": t("login.invalidCredentials"),
            "帳號已停用": t("login.accountDisabled"),
          };
          setError(errorI18nMap[backendError] || backendError);
        } catch {
          setError(err.message);
        }
      } else {
        setError(t("login.loginFailed"));
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
        <h2 className="text-2xl font-bold text-black">{t("login.welcomeBack")}</h2>
        <p className="text-sm text-gray-500 mt-1">
          {t("login.subtitle")}
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        {success && (
          <div className="flex items-center gap-2 rounded-lg bg-green-50 border border-green-200 px-4 py-2.5 text-sm text-green-700">
            <CheckCircle2 className="h-4 w-4 shrink-0" />
            {success}
          </div>
        )}

        {error && (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-2.5 text-sm text-red-600">
            {error}
          </div>
        )}

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
            autoComplete="current-password"
            className="h-11 bg-gray-50 border-gray-200 text-black focus:border-black focus:ring-black/10 placeholder:text-gray-400"
          />
        </div>

        <div className="flex justify-end">
          <Link
            href="/forgot-password"
            className="text-sm text-gray-500 hover:text-black transition-colors underline underline-offset-2"
          >
            {t("login.forgotPassword")}
          </Link>
        </div>

        <Button
          type="submit"
          className="w-full h-11 bg-black hover:bg-gray-800 text-white font-bold rounded-full transition-all duration-200 hover:shadow-lg"
          disabled={isLoading}
        >
          {isLoading ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              {t("login.loggingIn")}
            </>
          ) : (
            t("login.loginButton")
          )}
        </Button>

        <p className="text-center text-sm text-gray-500 pt-2">
          {t("login.noAccount")}
          <Link
            href="/register"
            className="font-medium text-black hover:text-gray-700 transition-colors underline underline-offset-2"
          >
            {t("login.registerNow")}
          </Link>
        </p>
      </form>
    </div>
  );
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
