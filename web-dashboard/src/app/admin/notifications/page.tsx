"use client";

import { useCallback, useEffect, useState, useMemo } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAdminSystemOverview, adminSendNotification } from "@/lib/api";
import { Send, Search, UserCircle, CheckSquare, Square } from "lucide-react";
import { toast } from "sonner";
import type { UserTradingSummary, AdminSendNotificationRequest } from "@/types";

type NotifColor = "BLUE" | "GREEN" | "YELLOW" | "RED";

export default function AdminNotificationsPage() {
  const { t } = useT();
  const [users, setUsers] = useState<UserTradingSummary[]>([]);
  const [usersLoading, setUsersLoading] = useState(true);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [searchQuery, setSearchQuery] = useState("");

  // Form state
  const [title, setTitle] = useState("");
  const [message, setMessage] = useState("");
  const [color, setColor] = useState<NotifColor>("BLUE");
  const [sending, setSending] = useState(false);

  // Load user list
  useEffect(() => {
    getAdminSystemOverview()
      .then((overview) => setUsers(overview.userSummaries))
      .catch(() => {})
      .finally(() => setUsersLoading(false));
  }, []);

  // Filter users by search
  const filteredUsers = useMemo(() => {
    if (!searchQuery.trim()) return users;
    const q = searchQuery.toLowerCase();
    return users.filter(
      (u) =>
        (u.name && u.name.toLowerCase().includes(q)) ||
        (u.email && u.email.toLowerCase().includes(q))
    );
  }, [users, searchQuery]);

  const toggleUser = useCallback((userId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(userId)) next.delete(userId);
      else next.add(userId);
      return next;
    });
  }, []);

  const selectAll = useCallback(() => {
    setSelectedIds(new Set(filteredUsers.map((u) => u.userId)));
  }, [filteredUsers]);

  const deselectAll = useCallback(() => {
    setSelectedIds(new Set());
  }, []);

  const handleSend = useCallback(async () => {
    if (selectedIds.size === 0 || !title.trim() || !message.trim()) return;

    setSending(true);
    try {
      const payload: AdminSendNotificationRequest = {
        userIds: Array.from(selectedIds),
        title: title.trim(),
        message: message.trim(),
        color,
      };
      const result = await adminSendNotification(payload);
      const summary = t("adminNotif.resultSummary")
        .replace("{success}", String(result.successCount))
        .replace("{fail}", String(result.failCount));
      toast.success(`${t("adminNotif.success")} — ${summary}`);

      // Reset form
      setTitle("");
      setMessage("");
      setSelectedIds(new Set());
    } catch {
      toast.error(t("adminNotif.failed"));
    } finally {
      setSending(false);
    }
  }, [selectedIds, title, message, color, t]);

  const colorOptions: { value: NotifColor; label: string; dot: string }[] = [
    { value: "BLUE", label: t("adminNotif.colorBlue"), dot: "bg-blue-400" },
    { value: "GREEN", label: t("adminNotif.colorGreen"), dot: "bg-green-400" },
    { value: "YELLOW", label: t("adminNotif.colorYellow"), dot: "bg-yellow-400" },
    { value: "RED", label: t("adminNotif.colorRed"), dot: "bg-red-400" },
  ];

  const canSend = selectedIds.size > 0 && title.trim() && message.trim() && !sending;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <div className="flex items-center gap-3 mb-1">
          <Send className="h-6 w-6 text-purple-400" />
          <h1 className="text-2xl font-bold">{t("adminNotif.title")}</h1>
        </div>
        <p className="text-sm text-muted-foreground ml-9">
          {t("adminNotif.description")}
        </p>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        {/* Left: User selection */}
        <div className="rounded-xl border border-border bg-card overflow-hidden">
          <div className="p-4 border-b border-border">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-sm font-semibold">{t("adminNotif.selectUsers")}</h2>
              <span className="text-xs text-muted-foreground">
                {t("adminNotif.selectedCount").replace("{count}", String(selectedIds.size))}
              </span>
            </div>

            {/* Search */}
            <div className="flex items-center gap-2 rounded-md border border-border bg-background px-3 py-1.5 mb-3">
              <Search className="h-4 w-4 text-muted-foreground shrink-0" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder={t("admin.search")}
                className="w-full bg-transparent text-sm focus:outline-none"
              />
            </div>

            {/* Select/Deselect all */}
            <div className="flex gap-2">
              <button
                onClick={selectAll}
                className="text-xs text-primary hover:underline"
              >
                {t("adminNotif.selectAll")}
              </button>
              <span className="text-xs text-muted-foreground">|</span>
              <button
                onClick={deselectAll}
                className="text-xs text-primary hover:underline"
              >
                {t("adminNotif.deselectAll")}
              </button>
            </div>
          </div>

          {/* User list */}
          <div className="overflow-y-auto max-h-[400px]">
            {usersLoading ? (
              <div className="flex items-center justify-center py-6">
                <div className="animate-spin rounded-full h-5 w-5 border-b-2 border-primary" />
              </div>
            ) : filteredUsers.length === 0 ? (
              <div className="py-4 text-center text-sm text-muted-foreground">
                {t("common.noData")}
              </div>
            ) : (
              filteredUsers.map((user) => {
                const selected = selectedIds.has(user.userId);
                return (
                  <button
                    key={user.userId}
                    onClick={() => toggleUser(user.userId)}
                    className={`w-full flex items-center gap-3 px-4 py-2.5 text-left hover:bg-accent/50 transition-colors ${
                      selected ? "bg-accent/30" : ""
                    }`}
                  >
                    {selected ? (
                      <CheckSquare className="h-4 w-4 text-primary shrink-0" />
                    ) : (
                      <Square className="h-4 w-4 text-muted-foreground shrink-0" />
                    )}
                    <UserCircle className="h-5 w-5 text-muted-foreground shrink-0" />
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium truncate">
                        {user.name || "unknown"}
                      </div>
                      <div className="text-xs text-muted-foreground truncate">
                        {user.email || "LINE"}
                      </div>
                    </div>
                  </button>
                );
              })
            )}
          </div>
        </div>

        {/* Right: Notification form */}
        <div className="rounded-xl border border-border bg-card p-5 space-y-5">
          {/* Title */}
          <div>
            <label className="block text-sm font-medium mb-1.5">
              {t("adminNotif.notifTitle")}
            </label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={t("adminNotif.notifTitlePlaceholder")}
              maxLength={100}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
            />
          </div>

          {/* Message */}
          <div>
            <label className="block text-sm font-medium mb-1.5">
              {t("adminNotif.notifMessage")}
            </label>
            <textarea
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder={t("adminNotif.notifMessagePlaceholder")}
              maxLength={2000}
              rows={6}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 resize-none"
            />
            <div className="text-right text-xs text-muted-foreground mt-1">
              {message.length} / 2000
            </div>
          </div>

          {/* Color */}
          <div>
            <label className="block text-sm font-medium mb-1.5">
              {t("adminNotif.color")}
            </label>
            <div className="flex flex-wrap gap-2">
              {colorOptions.map((opt) => (
                <button
                  key={opt.value}
                  onClick={() => setColor(opt.value)}
                  className={`inline-flex items-center gap-2 px-3 py-1.5 rounded-lg border text-xs font-medium transition-colors ${
                    color === opt.value
                      ? "border-primary bg-primary/10 text-foreground"
                      : "border-border text-muted-foreground hover:text-foreground"
                  }`}
                >
                  <span className={`h-2.5 w-2.5 rounded-full ${opt.dot}`} />
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          {/* Send button */}
          <button
            onClick={handleSend}
            disabled={!canSend}
            className="w-full flex items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Send className="h-4 w-4" />
            {sending ? t("adminNotif.sending") : t("adminNotif.send")}
          </button>
        </div>
      </div>
    </div>
  );
}
