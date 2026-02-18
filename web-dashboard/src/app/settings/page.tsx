"use client";

import { useEffect, useState } from "react";
import type { UserProfile, ApiKeyMetadata } from "@/types";
import { getUserProfile, getApiKeys, saveApiKey } from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";

export default function SettingsPage() {
  // Profile state
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [profileLoading, setProfileLoading] = useState(true);
  const [profileError, setProfileError] = useState<string | null>(null);

  // API Keys state
  const [apiKeys, setApiKeys] = useState<ApiKeyMetadata[]>([]);
  const [keysLoading, setKeysLoading] = useState(true);
  const [keysError, setKeysError] = useState<string | null>(null);

  // Form state
  const [exchange, setExchange] = useState("BINANCE");
  const [apiKeyInput, setApiKeyInput] = useState("");
  const [secretKeyInput, setSecretKeyInput] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveMessage, setSaveMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);

  // Fetch profile
  useEffect(() => {
    let cancelled = false;

    async function fetchProfile() {
      setProfileLoading(true);
      setProfileError(null);
      try {
        const data = await getUserProfile();
        if (!cancelled) setProfile(data);
      } catch (err) {
        if (!cancelled) setProfileError(err instanceof Error ? err.message : "載入失敗");
      } finally {
        if (!cancelled) setProfileLoading(false);
      }
    }

    fetchProfile();
    return () => { cancelled = true; };
  }, []);

  // Fetch API keys
  useEffect(() => {
    let cancelled = false;

    async function fetchKeys() {
      setKeysLoading(true);
      setKeysError(null);
      try {
        const data = await getApiKeys();
        if (!cancelled) setApiKeys(data);
      } catch (err) {
        if (!cancelled) setKeysError(err instanceof Error ? err.message : "載入失敗");
      } finally {
        if (!cancelled) setKeysLoading(false);
      }
    }

    fetchKeys();
    return () => { cancelled = true; };
  }, []);

  async function handleSaveApiKey() {
    if (!apiKeyInput.trim() || !secretKeyInput.trim()) {
      setSaveMessage({ type: "error", text: "請填寫 API Key 和 Secret Key" });
      return;
    }

    setSaving(true);
    setSaveMessage(null);
    try {
      const result = await saveApiKey({
        exchange,
        apiKey: apiKeyInput.trim(),
        secretKey: secretKeyInput.trim(),
      });
      setSaveMessage({ type: "success", text: result.message || "儲存成功" });
      setApiKeyInput("");
      setSecretKeyInput("");

      // Refresh API keys list
      const updatedKeys = await getApiKeys();
      setApiKeys(updatedKeys);
    } catch (err) {
      setSaveMessage({
        type: "error",
        text: err instanceof Error ? err.message : "儲存失敗",
      });
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">設定</h1>

      {/* Section 1: User Profile */}
      <Card>
        <CardHeader>
          <CardTitle>個人資料</CardTitle>
        </CardHeader>
        <CardContent>
          {profileLoading && (
            <div className="flex items-center justify-center py-8">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
            </div>
          )}

          {profileError && (
            <div className="text-center py-6 text-red-500">{profileError}</div>
          )}

          {!profileLoading && !profileError && profile && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <Label className="text-muted-foreground text-xs">User ID</Label>
                <p className="font-mono text-sm">{profile.userId}</p>
              </div>
              <div>
                <Label className="text-muted-foreground text-xs">Email</Label>
                <p className="text-sm">{profile.email}</p>
              </div>
              <div>
                <Label className="text-muted-foreground text-xs">名稱</Label>
                <p className="text-sm">{profile.name}</p>
              </div>
              <div>
                <Label className="text-muted-foreground text-xs">角色</Label>
                <p className="text-sm">
                  <Badge variant="outline">{profile.role}</Badge>
                </p>
              </div>
              <div>
                <Label className="text-muted-foreground text-xs">建立時間</Label>
                <p className="text-sm">{formatDateTime(profile.createdAt)}</p>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Section 2: API Key Management */}
      <Card>
        <CardHeader>
          <CardTitle>API Key 管理</CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {/* Current keys */}
          {keysLoading && (
            <div className="flex items-center justify-center py-8">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
            </div>
          )}

          {keysError && (
            <div className="text-center py-6 text-red-500">{keysError}</div>
          )}

          {!keysLoading && !keysError && (
            <>
              {apiKeys.length > 0 ? (
                <div className="space-y-3">
                  <Label className="text-muted-foreground text-xs">已設定的交易所</Label>
                  <div className="space-y-2">
                    {apiKeys.map((key) => (
                      <div
                        key={key.exchange}
                        className="flex items-center justify-between p-3 border rounded-lg"
                      >
                        <div className="flex items-center gap-3">
                          <span className="font-medium text-sm">{key.exchange}</span>
                          {key.hasApiKey ? (
                            <Badge className="bg-emerald-500/15 text-emerald-500 border-emerald-500/25">
                              已設定
                            </Badge>
                          ) : (
                            <Badge variant="secondary">未設定</Badge>
                          )}
                        </div>
                        {key.updatedAt && (
                          <span className="text-xs text-muted-foreground">
                            更新於 {formatDateTime(key.updatedAt)}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">尚未設定任何 API Key</p>
              )}
            </>
          )}

          <Separator />

          {/* Add / Update form */}
          <div className="space-y-4">
            <Label className="text-sm font-medium">新增 / 更新 API Key</Label>

            <div className="grid grid-cols-1 gap-4">
              <div className="space-y-2">
                <Label htmlFor="exchange" className="text-xs text-muted-foreground">
                  Exchange
                </Label>
                <Input
                  id="exchange"
                  value={exchange}
                  onChange={(e) => setExchange(e.target.value)}
                  placeholder="BINANCE"
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="apiKey" className="text-xs text-muted-foreground">
                  API Key
                </Label>
                <Input
                  id="apiKey"
                  type="password"
                  value={apiKeyInput}
                  onChange={(e) => setApiKeyInput(e.target.value)}
                  placeholder="輸入 API Key"
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="secretKey" className="text-xs text-muted-foreground">
                  Secret Key
                </Label>
                <Input
                  id="secretKey"
                  type="password"
                  value={secretKeyInput}
                  onChange={(e) => setSecretKeyInput(e.target.value)}
                  placeholder="輸入 Secret Key"
                />
              </div>
            </div>

            {saveMessage && (
              <p
                className={`text-sm ${
                  saveMessage.type === "success" ? "text-emerald-500" : "text-red-500"
                }`}
              >
                {saveMessage.text}
              </p>
            )}

            <Button onClick={handleSaveApiKey} disabled={saving}>
              {saving ? "儲存中..." : "儲存 API Key"}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
