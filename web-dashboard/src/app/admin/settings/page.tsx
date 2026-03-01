"use client";

import { useState } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { changePassword, apiLogout } from "@/lib/api";
import { toast } from "sonner";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Loader2, Shield } from "lucide-react";

export default function AdminSettingsPage() {
  const { t } = useT();

  const [currentPw, setCurrentPw] = useState("");
  const [newPw, setNewPw] = useState("");
  const [confirmPw, setConfirmPw] = useState("");
  const [pwError, setPwError] = useState("");
  const [pwSaving, setPwSaving] = useState(false);

  async function handleChangePassword() {
    setPwError("");

    if (newPw.length < 8) {
      setPwError(t("settings.passwordMinLength"));
      return;
    }
    if (newPw !== confirmPw) {
      setPwError(t("settings.passwordMismatch"));
      return;
    }

    setPwSaving(true);
    try {
      await changePassword({
        currentPassword: currentPw,
        newPassword: newPw,
        confirmPassword: confirmPw,
      });
      toast.success(t("settings.passwordChangedToast"));
      setCurrentPw("");
      setNewPw("");
      setConfirmPw("");

      // 2 秒後登出跳轉
      setTimeout(async () => {
        await apiLogout();
        localStorage.removeItem("userId");
        localStorage.removeItem("email");
        window.location.href = "/login";
      }, 2000);
    } catch (err: unknown) {
      if (err instanceof Error) {
        try {
          const parsed = JSON.parse(err.message);
          setPwError(parsed.error || err.message);
        } catch {
          setPwError(err.message);
        }
      } else {
        setPwError(t("common.saveFailed"));
      }
    } finally {
      setPwSaving(false);
    }
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("admin.adminSettings")}</h1>

      <div className="rounded-xl border border-border bg-card p-6">
        <div className="flex items-center gap-2 mb-1">
          <Shield className="h-5 w-5 text-purple-400" />
          <h2 className="text-lg font-semibold">
            {t("settings.changePassword")}
          </h2>
        </div>
        <p className="text-sm text-muted-foreground mb-4">
          {t("settings.navSecurityDesc")}
        </p>
        <Separator className="mb-6" />

        {pwError && (
          <div className="rounded-lg bg-red-500/10 border border-red-500/20 px-4 py-2.5 text-sm text-red-400 mb-4">
            {pwError}
          </div>
        )}

        <div className="space-y-4 max-w-md">
          <div className="space-y-2">
            <Label htmlFor="currentPw" className="text-sm text-muted-foreground">
              {t("settings.currentPassword")}
            </Label>
            <Input
              id="currentPw"
              type="password"
              value={currentPw}
              onChange={(e) => setCurrentPw(e.target.value)}
              placeholder="••••••••"
              autoComplete="current-password"
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="newPw" className="text-sm text-muted-foreground">
              {t("settings.newPassword")}
            </Label>
            <Input
              id="newPw"
              type="password"
              value={newPw}
              onChange={(e) => setNewPw(e.target.value)}
              placeholder="••••••••"
              minLength={8}
              autoComplete="new-password"
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="confirmPw" className="text-sm text-muted-foreground">
              {t("settings.confirmPassword")}
            </Label>
            <Input
              id="confirmPw"
              type="password"
              value={confirmPw}
              onChange={(e) => setConfirmPw(e.target.value)}
              placeholder="••••••••"
              minLength={8}
              autoComplete="new-password"
            />
          </div>

          <Button
            onClick={handleChangePassword}
            disabled={pwSaving || !currentPw || !newPw || !confirmPw}
          >
            {pwSaving ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                {t("settings.changingPassword")}
              </>
            ) : (
              t("settings.changePasswordButton")
            )}
          </Button>
        </div>
      </div>
    </div>
  );
}
