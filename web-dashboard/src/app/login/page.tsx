"use client";

import { Suspense, useState, useEffect, useRef, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import Image from "next/image";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Loader2, CheckCircle2 } from "lucide-react";

/** LINE brand color */
const LINE_GREEN = "#06C755";

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { login, oauthLogin } = useAuth();
  const { t } = useT();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [isOAuthLoading, setIsOAuthLoading] = useState(false);

  // 防止 ticket 重複交換
  const ticketProcessed = useRef(false);

  // 顯示提示訊息 + 處理 OAuth callback ticket
  useEffect(() => {
    if (searchParams.get("verified") === "true") {
      setSuccess(t("login.emailVerifiedSuccess"));
    }
    if (searchParams.get("reset") === "true") {
      setSuccess(t("login.passwordResetSuccess"));
    }

    // OAuth ticket 交換
    const oauthStatus = searchParams.get("oauth");
    const ticket = searchParams.get("ticket");

    if (oauthStatus === "pending" && ticket && !ticketProcessed.current) {
      ticketProcessed.current = true;
      handleOAuthTicket(ticket);
    }

    if (oauthStatus === "error") {
      setError(t("login.lineLoginError"));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams, t]);

  async function handleOAuthTicket(ticket: string) {
    setIsOAuthLoading(true);
    setError("");
    try {
      await oauthLogin(ticket);
      // 清除 URL 參數後跳轉
      router.replace("/");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t("login.lineLoginError"));
      // 清除 URL 中的 ticket 參數
      router.replace("/login");
    } finally {
      setIsOAuthLoading(false);
    }
  }

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
            "此帳號使用第三方登入，請用 LINE 登入": t("login.oauthOnlyAccount"),
          };
          setError(errorI18nMap[backendError] || backendError);
        } catch {
          // 直接映射非 JSON 錯誤
          const errorI18nMap: Record<string, string> = {
            "此帳號使用第三方登入，請用 LINE 登入": t("login.oauthOnlyAccount"),
          };
          setError(errorI18nMap[err.message] || err.message);
        }
      } else {
        setError(t("login.loginFailed"));
      }
    } finally {
      setIsLoading(false);
    }
  }

  function handleLineLogin() {
    // 導向後端 LINE OAuth 端點
    window.location.href = "/api/auth/oauth/line";
  }

  // OAuth ticket 交換中 → 顯示 loading
  if (isOAuthLoading) {
    return (
      <div className="rounded-[30px] bg-white p-8 shadow-[0_2px_12px_rgba(0,0,0,0.08)]">
        <div className="flex flex-col items-center gap-4 py-12">
          <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
          <p className="text-sm text-gray-500">{t("login.lineLoginProcessing")}</p>
        </div>
      </div>
    );
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

      <div className="space-y-4">
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

        {/* LINE Login Button */}
        <Button
          type="button"
          onClick={handleLineLogin}
          className="w-full h-11 font-bold rounded-full transition-all duration-200 hover:shadow-lg text-white"
          style={{ backgroundColor: LINE_GREEN }}
          disabled={isLoading}
        >
          <svg viewBox="0 0 24 24" className="mr-2 h-5 w-5 fill-current" aria-hidden="true">
            <path d="M19.365 9.863c.349 0 .63.285.63.631 0 .345-.281.63-.63.63H17.61v1.125h1.755c.349 0 .63.283.63.63 0 .344-.281.629-.63.629h-2.386c-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63h2.386c.346 0 .627.285.627.63 0 .349-.281.63-.63.63H17.61v1.125h1.755zm-3.855 3.016c0 .27-.174.51-.432.596-.064.021-.133.031-.199.031-.211 0-.391-.09-.51-.25l-2.443-3.317v2.94c0 .344-.279.629-.631.629-.346 0-.626-.285-.626-.629V8.108c0-.27.173-.51.43-.595.06-.023.136-.033.194-.033.195 0 .375.104.495.254l2.462 3.33V8.108c0-.345.282-.63.63-.63.345 0 .63.285.63.63v4.771zm-5.741 0c0 .344-.282.629-.631.629-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63.346 0 .628.285.628.63v4.771zm-2.466.629H4.917c-.345 0-.63-.285-.63-.629V8.108c0-.345.285-.63.63-.63.348 0 .63.285.63.63v4.141h1.756c.348 0 .629.283.629.63 0 .344-.282.629-.629.629M24 10.314C24 4.943 18.615.572 12 .572S0 4.943 0 10.314c0 4.811 4.27 8.842 10.035 9.608.391.082.923.258 1.058.59.12.301.079.766.038 1.08l-.164 1.02c-.045.301-.24 1.186 1.049.645 1.291-.539 6.916-4.078 9.436-6.975C23.176 14.393 24 12.458 24 10.314" />
          </svg>
          {t("login.lineLogin")}
        </Button>

        {/* Divider */}
        <div className="relative">
          <div className="absolute inset-0 flex items-center">
            <div className="w-full border-t border-gray-200" />
          </div>
          <div className="relative flex justify-center text-xs">
            <span className="bg-white px-3 text-gray-400">{t("login.orDivider")}</span>
          </div>
        </div>

        {/* Email/Password Form */}
        <form onSubmit={handleSubmit} className="space-y-4">
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
        </form>

        <p className="text-center text-sm text-gray-500 pt-2">
          {t("login.noAccount")}
          <Link
            href="/register"
            className="font-medium text-black hover:text-gray-700 transition-colors underline underline-offset-2"
          >
            {t("login.registerNow")}
          </Link>
        </p>
      </div>
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
