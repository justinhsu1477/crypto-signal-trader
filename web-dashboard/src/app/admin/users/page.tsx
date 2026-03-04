"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAdminUsers, updateAdminUser } from "@/lib/api";
import type { AdminUserListResponse, AdminUserSummary } from "@/types";
import { Users, UserCheck, ShieldCheck, Check, X, Power, ChevronUp, ChevronDown, ChevronsUpDown } from "lucide-react";
import { toast } from "sonner";

type SortField = "email" | "name" | "role" | "enabled" | "autoTradeEnabled" | "createdAt";
type SortDir = "asc" | "desc";

export default function AdminUsersPage() {
  const { t } = useT();
  const [data, setData] = useState<AdminUserListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [updating, setUpdating] = useState<string | null>(null);
  const [sortField, setSortField] = useState<SortField>("createdAt");
  const [sortDir, setSortDir] = useState<SortDir>("desc");

  const fetchUsers = useCallback(() => {
    setLoading(true);
    getAdminUsers()
      .then(setData)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  async function handleToggle(
    user: AdminUserSummary,
    field: "enabled" | "autoTradeEnabled"
  ) {
    setUpdating(user.userId);
    try {
      const res = await updateAdminUser(user.userId, {
        [field]: !user[field],
      });
      toast.success(t("admin.updateSuccess"));
      // Update local state
      setData((prev) =>
        prev
          ? {
              ...prev,
              users: prev.users.map((u) =>
                u.userId === user.userId ? res.user : u
              ),
            }
          : prev
      );
    } catch {
      toast.error(t("admin.updateFailed"));
    } finally {
      setUpdating(null);
    }
  }

  function toggleSort(field: SortField) {
    if (sortField === field) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortField(field);
      setSortDir(field === "createdAt" ? "desc" : "asc");
    }
  }

  const sorted = useMemo(() => {
    if (!data) return [];
    return [...data.users].sort((a, b) => {
      const dir = sortDir === "asc" ? 1 : -1;
      const av = a[sortField];
      const bv = b[sortField];
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      if (typeof av === "boolean") return (av === bv ? 0 : av ? -1 : 1) * dir;
      return String(av).localeCompare(String(bv)) * dir;
    });
  }, [data, sortField, sortDir]);

  function SortIcon({ field }: { field: SortField }) {
    if (sortField !== field) return <ChevronsUpDown className="h-3 w-3 opacity-40" />;
    return sortDir === "asc"
      ? <ChevronUp className="h-3 w-3" />
      : <ChevronDown className="h-3 w-3" />;
  }

  if (loading) {
    return (
      <div className="flex h-[60vh] items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  if (!data) {
    return (
      <div className="flex h-[60vh] items-center justify-center text-muted-foreground">
        Failed to load data
      </div>
    );
  }

  const stats = [
    { label: t("admin.totalUsers"), value: data.totalUsers, icon: Users, color: "text-blue-500" },
    { label: t("admin.activeUsers"), value: data.activeUsers, icon: UserCheck, color: "text-green-500" },
    { label: t("admin.adminCount"), value: data.adminUsers, icon: ShieldCheck, color: "text-purple-500" },
  ];

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("admin.userManagement")}</h1>

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4">
        {stats.map((s) => (
          <div key={s.label} className="rounded-xl border border-border bg-card p-4">
            <div className="flex items-center gap-2 mb-1">
              <s.icon className={`h-4 w-4 ${s.color}`} />
              <span className="text-xs text-muted-foreground">{s.label}</span>
            </div>
            <div className="text-xl font-bold">{s.value}</div>
          </div>
        ))}
      </div>

      {/* Users Table */}
      <div className="rounded-xl border border-border bg-card">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                {([
                  { field: "email" as SortField, label: t("admin.email"), align: "text-left" },
                  { field: "name" as SortField, label: t("admin.name"), align: "text-left" },
                  { field: "role" as SortField, label: t("admin.role"), align: "text-center" },
                  { field: "enabled" as SortField, label: t("admin.status"), align: "text-center" },
                  { field: "autoTradeEnabled" as SortField, label: t("admin.autoTrade"), align: "text-center" },
                  { field: "createdAt" as SortField, label: t("admin.createdAt"), align: "text-left" },
                ]).map((col) => (
                  <th
                    key={col.field}
                    onClick={() => toggleSort(col.field)}
                    className={`${col.align} px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground transition-colors`}
                  >
                    <span className="inline-flex items-center gap-1">
                      {col.label}
                      <SortIcon field={col.field} />
                    </span>
                  </th>
                ))}
                <th className="text-center px-4 py-3 font-medium">{t("admin.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((user) => {
                const isUpdating = updating === user.userId;
                return (
                  <tr
                    key={user.userId}
                    className="border-b border-border/50 hover:bg-accent/30 transition-colors"
                  >
                    <td className="px-4 py-3">
                      <div className="font-mono text-xs">{user.email || "-"}</div>
                      {user.loginMethods && user.loginMethods.length > 0 && (
                        <div className="flex gap-1 mt-0.5">
                          {user.loginMethods.map((m) => (
                            <span
                              key={m}
                              className={`inline-block px-1.5 py-px rounded text-[10px] font-medium leading-tight ${
                                m === "LINE"
                                  ? "bg-green-500/15 text-green-400"
                                  : "bg-gray-500/15 text-gray-400"
                              }`}
                            >
                              {m}
                            </span>
                          ))}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-3">{user.name || "-"}</td>
                    <td className="px-4 py-3 text-center">
                      <span
                        className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
                          user.role === "ADMIN"
                            ? "bg-purple-500/20 text-purple-400"
                            : "bg-blue-500/20 text-blue-400"
                        }`}
                      >
                        {user.role}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-center">
                      {user.enabled ? (
                        <Check className="h-4 w-4 text-green-500 mx-auto" />
                      ) : (
                        <X className="h-4 w-4 text-red-500 mx-auto" />
                      )}
                    </td>
                    <td className="px-4 py-3 text-center">
                      {user.autoTradeEnabled ? (
                        <Check className="h-4 w-4 text-green-500 mx-auto" />
                      ) : (
                        <X className="h-4 w-4 text-muted-foreground mx-auto" />
                      )}
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {user.createdAt
                        ? new Date(user.createdAt).toLocaleDateString()
                        : "-"}
                    </td>
                    <td className="px-4 py-3 text-center">
                      <div className="flex items-center justify-center gap-1">
                        <button
                          onClick={() => handleToggle(user, "enabled")}
                          disabled={isUpdating}
                          title={user.enabled ? t("admin.confirmDisable") : t("admin.confirmEnable")}
                          className={`p-1.5 rounded-lg transition-colors ${
                            user.enabled
                              ? "hover:bg-red-500/20 text-muted-foreground hover:text-red-400"
                              : "hover:bg-green-500/20 text-muted-foreground hover:text-green-400"
                          } disabled:opacity-50`}
                        >
                          <Power className="h-4 w-4" />
                        </button>
                        <button
                          onClick={() => handleToggle(user, "autoTradeEnabled")}
                          disabled={isUpdating}
                          title={t("admin.autoTrade")}
                          className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-50"
                        >
                          <span className="text-xs font-medium">AT</span>
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
