"use client";

import { useCallback, useEffect, useState, useMemo } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAdminSystemOverview, getAdminUserPerformance } from "@/lib/api";
import { SummaryCards } from "@/components/performance/summary-cards";
import { PnlChart } from "@/components/performance/pnl-chart";
import { SymbolStats } from "@/components/performance/symbol-stats";
import { SideComparison } from "@/components/performance/side-comparison";
import { ExitReasonChart } from "@/components/performance/exit-reason-chart";
import { TimeStats } from "@/components/performance/time-stats";
import { DayOfWeekChart } from "@/components/performance/day-of-week-chart";
import { DcaAnalysis } from "@/components/performance/dca-analysis";
import { ErrorBoundary } from "@/components/ui/error-boundary";
import { Search, TrendingUp, UserCircle } from "lucide-react";
import type { PerformanceStats, UserTradingSummary } from "@/types";

export default function AdminAnalyticsPage() {
  const { t } = useT();
  const [users, setUsers] = useState<UserTradingSummary[]>([]);
  const [usersLoading, setUsersLoading] = useState(true);
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [days, setDays] = useState(30);
  const [data, setData] = useState<PerformanceStats | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [dropdownOpen, setDropdownOpen] = useState(false);

  const PERIOD_OPTIONS = [
    { label: "7d", days: 7 },
    { label: "30d", days: 30 },
    { label: "90d", days: 90 },
    { label: "180d", days: 180 },
    { label: t("performance.all"), days: 3650 },
  ];

  // Load user list
  useEffect(() => {
    getAdminSystemOverview()
      .then((overview) => setUsers(overview.userSummaries))
      .catch(() => {})
      .finally(() => setUsersLoading(false));
  }, []);

  // Wrapped setters that also trigger loading state
  const selectUser = useCallback((userId: string) => {
    setSelectedUserId(userId);
    setLoading(true);
    setError(null);
  }, []);

  const changeDays = useCallback((d: number) => {
    setDays(d);
    if (selectedUserId) {
      setLoading(true);
      setError(null);
    }
  }, [selectedUserId]);

  // Load performance data when user or period changes
  useEffect(() => {
    if (!selectedUserId) return;

    let cancelled = false;

    getAdminUserPerformance(selectedUserId, days)
      .then((stats) => {
        if (!cancelled) setData(stats);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : t("common.loadFailed"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => { cancelled = true; };
  }, [selectedUserId, days, t]);

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

  const selectedUser = users.find((u) => u.userId === selectedUserId);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <TrendingUp className="h-6 w-6 text-purple-400" />
          <h1 className="text-2xl font-bold">{t("analytics.title")}</h1>
        </div>

        {/* Period selector */}
        {selectedUserId && (
          <div className="flex gap-1 rounded-lg bg-muted p-1">
            {PERIOD_OPTIONS.map((option) => (
              <button
                key={option.days}
                onClick={() => changeDays(option.days)}
                className={`rounded-md px-3 py-1 text-sm font-medium transition-colors ${
                  days === option.days
                    ? "bg-background text-foreground shadow-sm"
                    : "text-muted-foreground hover:text-foreground"
                }`}
              >
                {option.label}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* User selector */}
      <div className="relative">
        <div
          className="flex items-center gap-2 rounded-lg border border-border bg-card px-3 py-2.5 cursor-pointer hover:border-primary/50 transition-colors"
          onClick={() => setDropdownOpen(!dropdownOpen)}
        >
          <Search className="h-4 w-4 text-muted-foreground shrink-0" />
          {selectedUser ? (
            <div className="flex items-center gap-2 flex-1 min-w-0">
              <span className="text-sm font-medium truncate">{selectedUser.name || "unknown"}</span>
              {selectedUser.email && (
                <span className="text-xs text-muted-foreground truncate">{selectedUser.email}</span>
              )}
            </div>
          ) : (
            <span className="text-sm text-muted-foreground">{t("analytics.selectUser")}</span>
          )}
        </div>

        {dropdownOpen && (
          <>
            {/* Backdrop */}
            <div className="fixed inset-0 z-10" onClick={() => setDropdownOpen(false)} />

            {/* Dropdown */}
            <div className="absolute z-20 mt-1 w-full rounded-lg border border-border bg-card shadow-lg max-h-80 overflow-hidden">
              {/* Search input */}
              <div className="p-2 border-b border-border">
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder={t("admin.search")}
                  className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
                  autoFocus
                  onClick={(e) => e.stopPropagation()}
                />
              </div>

              {/* User list */}
              <div className="overflow-y-auto max-h-60">
                {usersLoading ? (
                  <div className="flex items-center justify-center py-6">
                    <div className="animate-spin rounded-full h-5 w-5 border-b-2 border-primary" />
                  </div>
                ) : filteredUsers.length === 0 ? (
                  <div className="py-4 text-center text-sm text-muted-foreground">
                    {t("common.noData")}
                  </div>
                ) : (
                  filteredUsers.map((user) => (
                    <button
                      key={user.userId}
                      onClick={() => {
                        selectUser(user.userId);
                        setDropdownOpen(false);
                        setSearchQuery("");
                      }}
                      className={`w-full flex items-center gap-3 px-3 py-2 text-left hover:bg-accent/50 transition-colors ${
                        user.userId === selectedUserId ? "bg-accent/30" : ""
                      }`}
                    >
                      <UserCircle className="h-5 w-5 text-muted-foreground shrink-0" />
                      <div className="flex-1 min-w-0">
                        <div className="text-sm font-medium truncate">{user.name || "unknown"}</div>
                        <div className="text-xs text-muted-foreground truncate">
                          {user.email || "LINE"}
                        </div>
                      </div>
                      <div className="text-right shrink-0">
                        <div className={`text-xs font-mono ${user.totalNetProfit >= 0 ? "text-green-400" : "text-red-400"}`}>
                          {user.totalNetProfit >= 0 ? "+" : ""}{user.totalNetProfit.toFixed(2)}
                        </div>
                        <div className="text-[10px] text-muted-foreground">
                          {user.closedTradeCount} {t("analytics.trades")}
                        </div>
                      </div>
                    </button>
                  ))
                )}
              </div>
            </div>
          </>
        )}
      </div>

      {/* Content area */}
      {!selectedUserId ? (
        /* Empty state */
        <div className="flex flex-col items-center justify-center h-[50vh] text-muted-foreground gap-3">
          <TrendingUp className="h-12 w-12" />
          <p>{t("analytics.selectUserHint")}</p>
        </div>
      ) : loading ? (
        <div className="flex h-[50vh] items-center justify-center">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
        </div>
      ) : error || !data ? (
        <div className="flex h-[50vh] items-center justify-center">
          <p className="text-red-500">{error ?? t("common.cannotLoad")}</p>
        </div>
      ) : (
        <div className="space-y-6">
          {/* Summary Cards */}
          <ErrorBoundary>
            <SummaryCards summary={data.summary} />
          </ErrorBoundary>

          {/* PnL Chart */}
          <ErrorBoundary>
            <PnlChart data={data.pnlCurve} />
          </ErrorBoundary>

          {/* Symbol Stats + Side Comparison */}
          <ErrorBoundary>
            <div className="grid gap-4 lg:grid-cols-2">
              <SymbolStats data={data.symbolStats} />
              <SideComparison data={data.sideComparison} />
            </div>
          </ErrorBoundary>

          {/* Exit Reason */}
          <ErrorBoundary>
            <ExitReasonChart data={data.exitReasonBreakdown} />
          </ErrorBoundary>

          {/* Time Stats */}
          <ErrorBoundary>
            <TimeStats
              weeklyStats={data.weeklyStats}
              monthlyStats={data.monthlyStats}
            />
          </ErrorBoundary>

          {/* Day of Week + DCA Analysis */}
          <ErrorBoundary>
            <div className="grid gap-4 lg:grid-cols-2">
              <DayOfWeekChart data={data.dayOfWeekStats} />
              <DcaAnalysis data={data.dcaAnalysis} />
            </div>
          </ErrorBoundary>
        </div>
      )}
    </div>
  );
}
