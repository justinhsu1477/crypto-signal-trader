"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import Image from "next/image";
import { useT } from "@/lib/i18n/i18n-context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Loader2, CheckCircle2, ArrowLeft } from "lucide-react";
import { forgotPassword } from "@/lib/api";

export default function ForgotPasswordPage() {
  const { t } = useT();
  const [email, setEmail] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [sent, setSent] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setIsLoading(true);

    try {
      await forgotPassword({ email });
    } catch {
      // 靜默處理 — 不論成功失敗都顯示相同訊息（防枚舉）
    } finally {
      setIsLoading(false);
      setSent(true);
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
        <h2 className="text-2xl font-bold">{t("forgotPassword.title")}</h2>
        <p className="text-sm text-muted-foreground mt-1">
          {t("forgotPassword.subtitle")}
        </p>
      </div>

      {sent ? (
        <div className="space-y-4">
          <div className="flex items-start gap-2 rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-4 py-3 text-sm text-emerald-400">
            <CheckCircle2 className="h-4 w-4 shrink-0 mt-0.5" />
            <span>{t("forgotPassword.sent")}</span>
          </div>

          <Link
            href="/login"
            className="flex items-center justify-center gap-2 text-sm text-emerald-400 hover:text-emerald-300 transition-colors pt-2"
          >
            <ArrowLeft className="h-4 w-4" />
            {t("forgotPassword.backToLogin")}
          </Link>
        </div>
      ) : (
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="email" className="text-sm text-muted-foreground">
              {t("forgotPassword.email")}
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

          <Button
            type="submit"
            className="w-full h-11 bg-emerald-600 hover:bg-emerald-500 text-white font-medium transition-all duration-200 hover:shadow-lg hover:shadow-emerald-500/20"
            disabled={isLoading}
          >
            {isLoading ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                {t("forgotPassword.sending")}
              </>
            ) : (
              t("forgotPassword.sendButton")
            )}
          </Button>

          <Link
            href="/login"
            className="flex items-center justify-center gap-2 text-sm text-muted-foreground hover:text-white transition-colors pt-2"
          >
            <ArrowLeft className="h-4 w-4" />
            {t("forgotPassword.backToLogin")}
          </Link>
        </form>
      )}
    </div>
  );
}
