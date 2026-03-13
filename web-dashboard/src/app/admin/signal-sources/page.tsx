"use client";

import { useEffect, useState, useCallback } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import {
  getAdminSignalSources,
  createAdminSignalSource,
  updateAdminSignalSource,
  deleteAdminSignalSource,
  getAdminSignalSourceUsers,
  assignAdminSignalSourceUsers,
  unassignAdminSignalSourceUser,
  toggleAdminSignalSourceUser,
  getAdminSignalSourcePerformances,
  getAdminUsers,
  getSignalSourceMonitorStatus,
  updateGlobalMonitorSettings,
} from "@/lib/api";
import type {
  SignalSourceResponse,
  CreateSignalSourceRequest,
  UpdateSignalSourceRequest,
  UserAssignmentResponse,
  SignalSourcePerformanceDto,
  AdminUserListResponse,
  MonitorStatusResponse,
  TradeMode,
} from "@/types";
import {
  Target,
  Plus,
  Pencil,
  Trash2,
  Users,
  BarChart3,
  Power,
  UserPlus,
  UserMinus,
  ChevronDown,
  ChevronUp,
  Loader2,
  Activity,
  Settings2,
  Globe,
  UserCheck,
  X,
  Radio,
} from "lucide-react";
import { toast } from "sonner";

export default function AdminSignalSourcesPage() {
  const { t } = useT();
  const [sources, setSources] = useState<SignalSourceResponse[]>([]);
  const [performances, setPerformances] = useState<SignalSourcePerformanceDto[]>([]);
  const [monitorStatus, setMonitorStatus] = useState<MonitorStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [editingSource, setEditingSource] = useState<SignalSourceResponse | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [assignedUsers, setAssignedUsers] = useState<UserAssignmentResponse[]>([]);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [allUsers, setAllUsers] = useState<AdminUserListResponse | null>(null);
  const [showAssignModal, setShowAssignModal] = useState(false);
  const [assignSourceId, setAssignSourceId] = useState<number | null>(null);
  const [selectedUserId, setSelectedUserId] = useState("");
  const [assigning, setAssigning] = useState(false);
  const [showGlobalSettings, setShowGlobalSettings] = useState(false);
  const [showChannelActivity, setShowChannelActivity] = useState(false);
  const [perfSortField, setPerfSortField] = useState<keyof SignalSourcePerformanceDto | null>(null);
  const [perfSortDir, setPerfSortDir] = useState<"asc" | "desc">("desc");

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const [s, p, m] = await Promise.all([
        getAdminSignalSources(),
        getAdminSignalSourcePerformances(),
        getSignalSourceMonitorStatus(),
      ]);
      setSources(s);
      setPerformances(p);
      setMonitorStatus(m);
    } catch {
      toast.error(t("common.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  async function handleExpandUsers(sourceId: number) {
    if (expandedId === sourceId) {
      setExpandedId(null);
      return;
    }
    setExpandedId(sourceId);
    setLoadingUsers(true);
    try {
      const users = await getAdminSignalSourceUsers(sourceId);
      setAssignedUsers(users);
    } catch {
      toast.error(t("common.loadFailed"));
    } finally {
      setLoadingUsers(false);
    }
  }

  async function handleDelete(id: number) {
    if (!confirm(t("signalSources.deleteConfirm"))) return;
    try {
      await deleteAdminSignalSource(id);
      toast.success(t("signalSources.deleteSuccess"));
      fetchData();
      if (expandedId === id) setExpandedId(null);
    } catch {
      toast.error(t("common.saveFailed"));
    }
  }

  async function handleToggleEnabled(source: SignalSourceResponse) {
    try {
      await updateAdminSignalSource(source.id, { enabled: !source.enabled });
      toast.success(t("signalSources.updateSuccess"));
      fetchData();
    } catch {
      toast.error(t("common.saveFailed"));
    }
  }

  async function handleUnassign(sourceId: number, userId: string) {
    try {
      await unassignAdminSignalSourceUser(sourceId, userId);
      toast.success(t("signalSources.unassignSuccess"));
      const users = await getAdminSignalSourceUsers(sourceId);
      setAssignedUsers(users);
      fetchData();
    } catch {
      toast.error(t("common.saveFailed"));
    }
  }

  async function handleToggleUserAssignment(sourceId: number, userId: string, enabled: boolean) {
    try {
      await toggleAdminSignalSourceUser(sourceId, userId, !enabled);
      const users = await getAdminSignalSourceUsers(sourceId);
      setAssignedUsers(users);
    } catch {
      toast.error(t("common.saveFailed"));
    }
  }

  async function openAssignModal(sourceId: number) {
    setAssignSourceId(sourceId);
    setSelectedUserId("");
    setShowAssignModal(true);
    if (!allUsers) {
      try {
        const u = await getAdminUsers();
        setAllUsers(u);
      } catch {
        toast.error(t("common.loadFailed"));
      }
    }
  }

  async function handleAssignUser() {
    if (!assignSourceId || !selectedUserId) return;
    setAssigning(true);
    try {
      await assignAdminSignalSourceUsers(assignSourceId, [selectedUserId]);
      toast.success(t("signalSources.assignSuccess"));
      setShowAssignModal(false);
      const users = await getAdminSignalSourceUsers(assignSourceId);
      setAssignedUsers(users);
      fetchData();
    } catch (err) {
      const msg = err instanceof Error ? err.message : t("common.saveFailed");
      toast.error(msg);
    } finally {
      setAssigning(false);
    }
  }

  function getPerformance(sourceId: number) {
    return performances.find((p) => p.sourceId === sourceId);
  }

  function handlePerfSort(field: keyof SignalSourcePerformanceDto) {
    if (perfSortField === field) {
      setPerfSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setPerfSortField(field);
      setPerfSortDir(field === "displayName" ? "asc" : "desc");
    }
  }

  const sortedPerformances = perfSortField
    ? [...performances].sort((a, b) => {
        const av = a[perfSortField];
        const bv = b[perfSortField];
        const cmp = typeof av === "string" ? av.localeCompare(bv as string) : (av as number) - (bv as number);
        return perfSortDir === "asc" ? cmp : -cmp;
      })
    : performances;

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold flex items-center gap-2">
            <Target className="h-6 w-6 text-purple-400" />
            {t("signalSources.title")}
          </h1>
          <p className="text-sm text-muted-foreground mt-1">
            {t("signalSources.subtitle")}
          </p>
        </div>
        <button
          onClick={() => { setEditingSource(null); setShowCreateModal(true); }}
          className="flex items-center gap-2 px-4 py-2 bg-purple-600 hover:bg-purple-700 text-white rounded-lg text-sm font-medium transition-colors"
        >
          <Plus className="h-4 w-4" />
          {t("signalSources.create")}
        </button>
      </div>

      {/* Monitor Status Card */}
      {monitorStatus && (
        <div className="bg-card border border-border rounded-xl p-4">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold flex items-center gap-2">
              <Activity className="h-4 w-4 text-purple-400" />
              {t("signalSources.monitorStatus")}
            </h2>
            <div className="flex items-center gap-2">
              <button
                onClick={() => setShowChannelActivity(!showChannelActivity)}
                className="flex items-center gap-1 text-xs px-3 py-1.5 border border-border rounded-md hover:bg-accent transition-colors"
              >
                <Radio className="h-3 w-3" />
                {t("signalSources.channelActivity")}
                {showChannelActivity ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
              </button>
              <button
                onClick={() => setShowGlobalSettings(!showGlobalSettings)}
                className="flex items-center gap-1 text-xs px-3 py-1.5 border border-border rounded-md hover:bg-accent transition-colors"
              >
                <Settings2 className="h-3 w-3" />
                {t("signalSources.globalSettings")}
                {showGlobalSettings ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
              </button>
            </div>
          </div>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
            <div className="flex items-center gap-2">
              <div className={`w-2.5 h-2.5 rounded-full ${monitorStatus.monitorOnline ? "bg-green-500 animate-pulse" : "bg-red-500"}`} />
              <span className={monitorStatus.monitorOnline ? "text-green-400" : "text-red-400"}>
                {monitorStatus.monitorOnline ? t("signalSources.monitorOnline") : t("signalSources.monitorOffline")}
              </span>
            </div>
            <div>
              <span className="text-muted-foreground text-xs">{t("signalSources.lastHeartbeat")}</span>
              <div className="font-mono text-xs">
                {monitorStatus.lastHeartbeat
                  ? new Date(monitorStatus.lastHeartbeat).toLocaleTimeString()
                  : "—"}
              </div>
            </div>
            <div>
              <span className="text-muted-foreground text-xs">{t("signalSources.connectedMonitors")}</span>
              <div className="font-mono text-xs">{monitorStatus.connectedMonitors}</div>
            </div>
            <div>
              <span className="text-muted-foreground text-xs">{t("signalSources.configVersion")}</span>
              <div className="font-mono text-xs">v{monitorStatus.configVersion}</div>
            </div>
          </div>

          {/* Channel Activity (collapsible) */}
          {showChannelActivity && (
            <ChannelActivityPanel
              channelLastSeen={monitorStatus.channelLastSeen}
              sources={sources}
              t={t}
            />
          )}

          {/* Global Settings (collapsible) */}
          {showGlobalSettings && (
            <GlobalSettingsPanel
              monitorStatus={monitorStatus}
              t={t}
              onSaved={fetchData}
            />
          )}
        </div>
      )}

      {/* Source list */}
      {sources.length === 0 ? (
        <div className="text-center py-20 text-muted-foreground">
          <Target className="h-12 w-12 mx-auto mb-4 opacity-50" />
          <p>{t("signalSources.noSources")}</p>
        </div>
      ) : (
        <div className="space-y-4">
          {sources.map((source) => {
            const perf = getPerformance(source.id);
            const isExpanded = expandedId === source.id;
            const isGlobal = source.routingMode === "GLOBAL";

            return (
              <div key={source.id} className="bg-card border border-border rounded-xl overflow-hidden">
                {/* Source row */}
                <div className="p-4 flex items-center gap-4">
                  {/* Status dot */}
                  <div className={`w-3 h-3 rounded-full flex-shrink-0 ${source.enabled ? "bg-green-500" : "bg-gray-500"}`} />

                  {/* Info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-semibold truncate">{source.displayName}</span>
                      <span className="text-xs text-muted-foreground">({source.name})</span>
                      {/* Routing mode badge */}
                      <span className={`inline-flex items-center gap-1 text-xs px-1.5 py-0.5 rounded-full ${
                        isGlobal
                          ? "bg-blue-500/15 text-blue-400"
                          : "bg-purple-500/15 text-purple-400"
                      }`}>
                        {isGlobal ? <Globe className="h-3 w-3" /> : <UserCheck className="h-3 w-3" />}
                        {isGlobal ? t("signalSources.routingGlobal") : t("signalSources.routingAssigned")}
                      </span>
                      {/* Trade mode badge */}
                      {source.tradeMode && source.tradeMode !== "AUTO" && (
                        <span className={`text-xs px-1.5 py-0.5 rounded-full ${
                          source.tradeMode === "SHADOW"
                            ? "bg-yellow-500/15 text-yellow-400"
                            : "bg-orange-500/15 text-orange-400"
                        }`}>
                          {source.tradeMode === "SHADOW" ? t("signalSources.tradeShadow") : t("signalSources.tradeManual")}
                        </span>
                      )}
                    </div>
                    <div className="text-xs text-muted-foreground mt-0.5 space-x-3">
                      {source.channelId && <span>CH: {source.channelId}</span>}
                      {source.guildId && <span>GD: {source.guildId}</span>}
                    </div>
                  </div>

                  {/* Performance stats */}
                  {perf && perf.tradeCount > 0 && (
                    <div className="hidden sm:flex items-center gap-4 text-xs">
                      <div className="text-center">
                        <div className="text-muted-foreground">{t("signalSources.trades")}</div>
                        <div className="font-mono font-semibold">{perf.tradeCount}</div>
                      </div>
                      <div className="text-center">
                        <div className="text-muted-foreground">{t("signalSources.winRate")}</div>
                        <div className={`font-mono font-semibold ${perf.winRate >= 50 ? "text-green-400" : "text-red-400"}`}>
                          {perf.winRate}%
                        </div>
                      </div>
                      <div className="text-center">
                        <div className="text-muted-foreground">{t("signalSources.totalPnl")}</div>
                        <div className={`font-mono font-semibold ${perf.totalPnl >= 0 ? "text-green-400" : "text-red-400"}`}>
                          ${perf.totalPnl.toFixed(2)}
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Assigned count badge (hide for GLOBAL) */}
                  {!isGlobal && (
                    <div className="flex items-center gap-1 px-2 py-1 bg-accent rounded-md text-xs">
                      <Users className="h-3 w-3" />
                      <span>{source.assignedUserCount}</span>
                    </div>
                  )}

                  {/* Actions */}
                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => handleToggleEnabled(source)}
                      className={`p-1.5 rounded-md hover:bg-accent transition-colors ${source.enabled ? "text-green-400" : "text-gray-400"}`}
                      title={source.enabled ? t("signalSources.disabled") : t("signalSources.enabled")}
                    >
                      <Power className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => { setEditingSource(source); setShowCreateModal(true); }}
                      className="p-1.5 rounded-md hover:bg-accent text-muted-foreground transition-colors"
                    >
                      <Pencil className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => handleDelete(source.id)}
                      className="p-1.5 rounded-md hover:bg-accent text-red-400 transition-colors"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                    {/* Only show expand for ASSIGNED mode */}
                    {!isGlobal && (
                      <button
                        onClick={() => handleExpandUsers(source.id)}
                        className="p-1.5 rounded-md hover:bg-accent text-muted-foreground transition-colors"
                      >
                        {isExpanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                      </button>
                    )}
                  </div>
                </div>

                {/* Expanded: assigned users (only for ASSIGNED mode) */}
                {isExpanded && !isGlobal && (
                  <div className="border-t border-border bg-accent/30 p-4">
                    <div className="flex items-center justify-between mb-3">
                      <h3 className="text-sm font-semibold flex items-center gap-1.5">
                        <Users className="h-4 w-4" />
                        {t("signalSources.assignedUsers")}
                      </h3>
                      <button
                        onClick={() => openAssignModal(source.id)}
                        className="flex items-center gap-1 text-xs px-3 py-1.5 bg-purple-600 hover:bg-purple-700 text-white rounded-md transition-colors"
                      >
                        <UserPlus className="h-3 w-3" />
                        {t("signalSources.assignUser")}
                      </button>
                    </div>

                    {loadingUsers ? (
                      <div className="flex justify-center py-4">
                        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
                      </div>
                    ) : assignedUsers.length === 0 ? (
                      <p className="text-sm text-muted-foreground py-2">{t("common.noData")}</p>
                    ) : (
                      <div className="space-y-2">
                        {assignedUsers.map((user) => (
                          <div
                            key={user.userId}
                            className="flex items-center justify-between bg-card rounded-lg px-3 py-2 border border-border"
                          >
                            <div className="flex items-center gap-3">
                              <div className={`w-2 h-2 rounded-full ${user.enabled ? "bg-green-500" : "bg-gray-500"}`} />
                              <div>
                                <span className="text-sm font-medium">{user.name || user.email || user.userId}</span>
                                {user.email && user.name && (
                                  <span className="text-xs text-muted-foreground ml-2">{user.email}</span>
                                )}
                              </div>
                            </div>
                            <div className="flex items-center gap-1">
                              <button
                                onClick={() => handleToggleUserAssignment(source.id, user.userId, user.enabled)}
                                className={`p-1 rounded-md hover:bg-accent transition-colors ${user.enabled ? "text-green-400" : "text-gray-400"}`}
                              >
                                <Power className="h-3.5 w-3.5" />
                              </button>
                              <button
                                onClick={() => handleUnassign(source.id, user.userId)}
                                className="p-1 rounded-md hover:bg-accent text-red-400 transition-colors"
                              >
                                <UserMinus className="h-3.5 w-3.5" />
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Performance comparison */}
      {performances.length > 0 && (
        <div className="bg-card border border-border rounded-xl p-4">
          <h2 className="text-lg font-semibold flex items-center gap-2 mb-4">
            <BarChart3 className="h-5 w-5 text-purple-400" />
            {t("signalSources.performance")}
          </h2>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-muted-foreground border-b border-border">
                  <SortableTh field="displayName" label={t("signalSources.displayName")} align="left"
                    sortField={perfSortField} sortDir={perfSortDir} onSort={handlePerfSort} />
                  <SortableTh field="tradeCount" label={t("signalSources.trades")} align="right"
                    sortField={perfSortField} sortDir={perfSortDir} onSort={handlePerfSort} />
                  <SortableTh field="winRate" label={t("signalSources.winRate")} align="right"
                    sortField={perfSortField} sortDir={perfSortDir} onSort={handlePerfSort} />
                  <SortableTh field="totalPnl" label={t("signalSources.totalPnl")} align="right"
                    sortField={perfSortField} sortDir={perfSortDir} onSort={handlePerfSort} />
                  <SortableTh field="avgPnl" label={t("signalSources.avgPnl")} align="right"
                    sortField={perfSortField} sortDir={perfSortDir} onSort={handlePerfSort} last />
                </tr>
              </thead>
              <tbody>
                {sortedPerformances.map((p) => (
                  <tr key={p.sourceId} className="border-b border-border/50">
                    <td className="py-2 pr-4 font-medium">{p.displayName}</td>
                    <td className="py-2 pr-4 text-right font-mono">{p.tradeCount}</td>
                    <td className={`py-2 pr-4 text-right font-mono ${p.winRate >= 50 ? "text-green-400" : "text-red-400"}`}>
                      {p.winRate}%
                    </td>
                    <td className={`py-2 pr-4 text-right font-mono ${p.totalPnl >= 0 ? "text-green-400" : "text-red-400"}`}>
                      ${p.totalPnl.toFixed(2)}
                    </td>
                    <td className={`py-2 text-right font-mono ${p.avgPnl >= 0 ? "text-green-400" : "text-red-400"}`}>
                      ${p.avgPnl.toFixed(2)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Create / Edit Modal */}
      {showCreateModal && (
        <CreateEditModal
          source={editingSource}
          t={t}
          onClose={() => { setShowCreateModal(false); setEditingSource(null); }}
          onSaved={() => { setShowCreateModal(false); setEditingSource(null); fetchData(); }}
        />
      )}

      {/* Assign User Modal */}
      {showAssignModal && assignSourceId && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60" onClick={() => setShowAssignModal(false)}>
          <div className="bg-card border border-border rounded-xl p-6 w-full max-w-md mx-4" onClick={(e) => e.stopPropagation()}>
            <h3 className="text-lg font-semibold mb-4">{t("signalSources.assignUser")}</h3>
            <p className="text-sm text-muted-foreground mb-3">{t("signalSources.selectUser")}</p>
            <select
              value={selectedUserId}
              onChange={(e) => setSelectedUserId(e.target.value)}
              className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm mb-4"
            >
              <option value="">--</option>
              {allUsers?.users.map((u) => (
                <option key={u.userId} value={u.userId}>
                  {u.name || u.email} ({u.userId.slice(0, 8)})
                </option>
              ))}
            </select>
            <div className="flex justify-end gap-2">
              <button
                onClick={() => setShowAssignModal(false)}
                className="px-4 py-2 text-sm rounded-lg border border-border hover:bg-accent transition-colors"
              >
                {t("common.cancel")}
              </button>
              <button
                onClick={handleAssignUser}
                disabled={!selectedUserId || assigning}
                className="px-4 py-2 text-sm rounded-lg bg-purple-600 hover:bg-purple-700 text-white disabled:opacity-50 transition-colors"
              >
                {assigning ? t("common.saving") : t("common.confirm")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Channel Activity Panel ───

function getChannelStatus(lastSeenMs: number | undefined): {
  color: string;
  dotColor: string;
  label: string;
  labelKey: string;
  relativeTime: string;
} {
  if (lastSeenMs === undefined) {
    return {
      color: "text-gray-500",
      dotColor: "bg-gray-500",
      label: "未收到",
      labelKey: "channelNever",
      relativeTime: "—",
    };
  }

  const now = Date.now();
  const diffMs = now - lastSeenMs;
  const diffMin = Math.floor(diffMs / 60_000);
  const diffHr = Math.floor(diffMs / 3_600_000);
  const diffDay = Math.floor(diffMs / 86_400_000);

  let relativeTime: string;
  if (diffMin < 1) relativeTime = "<1m";
  else if (diffMin < 60) relativeTime = `${diffMin}m`;
  else if (diffHr < 24) relativeTime = `${diffHr}h`;
  else relativeTime = `${diffDay}d`;

  if (diffMs < 3_600_000) {
    // < 1 hour → active (green)
    return { color: "text-green-400", dotColor: "bg-green-500", label: "活躍", labelKey: "channelActive", relativeTime };
  } else if (diffMs < 21_600_000) {
    // 1-6 hours → idle (yellow)
    return { color: "text-yellow-400", dotColor: "bg-yellow-500", label: "閒置", labelKey: "channelIdle", relativeTime };
  } else {
    // > 6 hours → silent (red)
    return { color: "text-red-400", dotColor: "bg-red-500", label: "靜默", labelKey: "channelSilent", relativeTime };
  }
}

function SortableTh({
  field,
  label,
  align,
  sortField,
  sortDir,
  onSort,
  last,
}: {
  field: keyof SignalSourcePerformanceDto;
  label: string;
  align: "left" | "right";
  sortField: keyof SignalSourcePerformanceDto | null;
  sortDir: "asc" | "desc";
  onSort: (f: keyof SignalSourcePerformanceDto) => void;
  last?: boolean;
}) {
  const active = sortField === field;
  return (
    <th
      className={`pb-2 ${last ? "" : "pr-4"} ${align === "right" ? "text-right" : ""} cursor-pointer select-none hover:text-foreground transition-colors`}
      onClick={() => onSort(field)}
    >
      <span className="inline-flex items-center gap-1">
        {align === "right" && active && (
          sortDir === "asc" ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />
        )}
        {label}
        {align === "left" && active && (
          sortDir === "asc" ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />
        )}
      </span>
    </th>
  );
}

function ChannelActivityPanel({
  channelLastSeen,
  sources,
  t,
}: {
  channelLastSeen: Record<string, number> | null;
  sources: SignalSourceResponse[];
  t: (key: string) => string;
}) {
  // Sources that have a channelId configured
  const channelSources = sources.filter((s) => s.channelId);
  // Channel IDs from channelLastSeen that don't have a matching SignalSource
  const knownChannelIds = new Set(channelSources.map((s) => s.channelId));
  const unmappedChannelIds = channelLastSeen
    ? Object.keys(channelLastSeen).filter((id) => !knownChannelIds.has(id))
    : [];

  const hasAnyChannel = channelSources.length > 0 || unmappedChannelIds.length > 0;

  if (!hasAnyChannel) {
    return (
      <div className="mt-3 pt-3 border-t border-border text-center text-xs text-muted-foreground py-4">
        {t("signalSources.noSources")}
      </div>
    );
  }

  return (
    <div className="mt-3 pt-3 border-t border-border">
      {!channelLastSeen || Object.keys(channelLastSeen).length === 0 ? (
        <div className="text-center text-xs text-muted-foreground py-4">
          {t("signalSources.noChannelData")}
        </div>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-2">
          {/* Mapped sources (have SignalSource record) */}
          {channelSources.map((source) => {
            const lastSeen = source.channelId ? channelLastSeen[source.channelId] : undefined;
            const status = getChannelStatus(lastSeen);
            return (
              <div
                key={source.id}
                className="flex items-center gap-2 px-3 py-2 bg-accent/30 rounded-lg text-xs"
              >
                <div className={`w-2 h-2 rounded-full flex-shrink-0 ${status.dotColor}`} />
                <span className="truncate font-medium">{source.displayName || source.name}</span>
                <span className={`ml-auto flex-shrink-0 font-mono ${status.color}`}>
                  {status.relativeTime}
                </span>
              </div>
            );
          })}
          {/* Unmapped channels (have activity but no SignalSource record) */}
          {unmappedChannelIds.map((channelId) => {
            const lastSeen = channelLastSeen[channelId];
            const status = getChannelStatus(lastSeen);
            return (
              <div
                key={channelId}
                className="flex items-center gap-2 px-3 py-2 bg-accent/30 rounded-lg text-xs border border-dashed border-yellow-500/30"
              >
                <div className={`w-2 h-2 rounded-full flex-shrink-0 ${status.dotColor}`} />
                <span className="truncate font-medium font-mono text-yellow-400" title={channelId}>
                  {channelId.slice(0, 6)}…{channelId.slice(-4)}
                </span>
                <span className={`ml-auto flex-shrink-0 font-mono ${status.color}`}>
                  {status.relativeTime}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ─── Global Settings Panel ───

function GlobalSettingsPanel({
  monitorStatus,
  t,
  onSaved,
}: {
  monitorStatus: MonitorStatusResponse;
  t: (key: string) => string;
  onSaved: () => void;
}) {
  const [authorIds, setAuthorIds] = useState<string[]>(monitorStatus.authorIds || []);
  const [ignoreKeywords, setIgnoreKeywords] = useState<string[]>(monitorStatus.ignoreKeywords || []);
  const [newAuthorId, setNewAuthorId] = useState("");
  const [newKeyword, setNewKeyword] = useState("");
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    setSaving(true);
    try {
      await updateGlobalMonitorSettings({ authorIds, ignoreKeywords });
      toast.success(t("signalSources.globalSettingsUpdated"));
      onSaved();
    } catch {
      toast.error(t("common.saveFailed"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="mt-4 pt-4 border-t border-border space-y-4">
      {/* Author IDs */}
      <div>
        <label className="text-xs font-medium text-muted-foreground">{t("signalSources.authorIds")}</label>
        <div className="flex flex-wrap gap-1.5 mt-1.5">
          {authorIds.map((id) => (
            <span key={id} className="inline-flex items-center gap-1 text-xs bg-accent px-2 py-1 rounded-md font-mono">
              {id}
              <button onClick={() => setAuthorIds(authorIds.filter((a) => a !== id))} className="text-muted-foreground hover:text-red-400">
                <X className="h-3 w-3" />
              </button>
            </span>
          ))}
          <div className="flex items-center gap-1">
            <input
              type="text"
              value={newAuthorId}
              onChange={(e) => setNewAuthorId(e.target.value)}
              placeholder="ID"
              className="w-32 bg-background border border-border rounded px-2 py-1 text-xs font-mono"
              onKeyDown={(e) => {
                if (e.key === "Enter" && newAuthorId.trim()) {
                  setAuthorIds([...authorIds, newAuthorId.trim()]);
                  setNewAuthorId("");
                }
              }}
            />
            <button
              onClick={() => {
                if (newAuthorId.trim()) {
                  setAuthorIds([...authorIds, newAuthorId.trim()]);
                  setNewAuthorId("");
                }
              }}
              className="text-xs px-2 py-1 bg-accent rounded hover:bg-accent/80 transition-colors"
            >
              {t("signalSources.addItem")}
            </button>
          </div>
        </div>
      </div>

      {/* Ignore Keywords */}
      <div>
        <label className="text-xs font-medium text-muted-foreground">{t("signalSources.ignoreKeywords")}</label>
        <div className="flex flex-wrap gap-1.5 mt-1.5">
          {ignoreKeywords.map((kw) => (
            <span key={kw} className="inline-flex items-center gap-1 text-xs bg-accent px-2 py-1 rounded-md">
              {kw}
              <button onClick={() => setIgnoreKeywords(ignoreKeywords.filter((k) => k !== kw))} className="text-muted-foreground hover:text-red-400">
                <X className="h-3 w-3" />
              </button>
            </span>
          ))}
          <div className="flex items-center gap-1">
            <input
              type="text"
              value={newKeyword}
              onChange={(e) => setNewKeyword(e.target.value)}
              placeholder="keyword"
              className="w-32 bg-background border border-border rounded px-2 py-1 text-xs"
              onKeyDown={(e) => {
                if (e.key === "Enter" && newKeyword.trim()) {
                  setIgnoreKeywords([...ignoreKeywords, newKeyword.trim()]);
                  setNewKeyword("");
                }
              }}
            />
            <button
              onClick={() => {
                if (newKeyword.trim()) {
                  setIgnoreKeywords([...ignoreKeywords, newKeyword.trim()]);
                  setNewKeyword("");
                }
              }}
              className="text-xs px-2 py-1 bg-accent rounded hover:bg-accent/80 transition-colors"
            >
              {t("signalSources.addItem")}
            </button>
          </div>
        </div>
      </div>

      <div className="flex justify-end">
        <button
          onClick={handleSave}
          disabled={saving}
          className="px-4 py-1.5 text-xs rounded-lg bg-purple-600 hover:bg-purple-700 text-white disabled:opacity-50 transition-colors"
        >
          {saving ? t("common.saving") : t("signalSources.saveSettings")}
        </button>
      </div>
    </div>
  );
}

// ─── Create / Edit Modal ───

function CreateEditModal({
  source,
  t,
  onClose,
  onSaved,
}: {
  source: SignalSourceResponse | null;
  t: (key: string) => string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const isEdit = !!source;
  const [name, setName] = useState(source?.name || "");
  const [displayName, setDisplayName] = useState(source?.displayName || "");
  const [channelId, setChannelId] = useState(source?.channelId || "");
  const [guildId, setGuildId] = useState(source?.guildId || "");
  const [description, setDescription] = useState(source?.description || "");
  const [routingMode, setRoutingMode] = useState<"GLOBAL" | "ASSIGNED">(source?.routingMode || "ASSIGNED");
  const [tradeMode, setTradeMode] = useState<TradeMode>(source?.tradeMode || "AUTO");
  const [riskMultiplier, setRiskMultiplier] = useState(source?.riskMultiplier ?? 1.0);
  const [saving, setSaving] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    try {
      if (isEdit && source) {
        const req: UpdateSignalSourceRequest = { name, displayName, description, routingMode, tradeMode, riskMultiplier };
        await updateAdminSignalSource(source.id, req);
        toast.success(t("signalSources.updateSuccess"));
      } else {
        const req: CreateSignalSourceRequest = {
          name,
          displayName,
          channelId: channelId || undefined,
          guildId: guildId || undefined,
          description: description || undefined,
          routingMode,
          tradeMode,
          riskMultiplier,
        };
        await createAdminSignalSource(req);
        toast.success(t("signalSources.createSuccess"));
      }
      onSaved();
    } catch (err) {
      const msg = err instanceof Error ? err.message : t("common.saveFailed");
      toast.error(msg);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60" onClick={onClose}>
      <div className="bg-card border border-border rounded-xl p-6 w-full max-w-lg mx-4" onClick={(e) => e.stopPropagation()}>
        <h3 className="text-lg font-semibold mb-4">
          {isEdit ? t("signalSources.edit") : t("signalSources.create")}
        </h3>
        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className="block text-sm text-muted-foreground mb-1">{t("signalSources.name")} *</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t("signalSources.namePlaceholder")}
              className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm"
              required
            />
          </div>
          <div>
            <label className="block text-sm text-muted-foreground mb-1">{t("signalSources.displayName")} *</label>
            <input
              type="text"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder={t("signalSources.displayNamePlaceholder")}
              className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm"
              required
            />
          </div>
          {!isEdit && (
            <>
              <div>
                <label className="block text-sm text-muted-foreground mb-1">{t("signalSources.channelId")}</label>
                <input
                  type="text"
                  value={channelId}
                  onChange={(e) => setChannelId(e.target.value)}
                  className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm font-mono"
                />
              </div>
              <div>
                <label className="block text-sm text-muted-foreground mb-1">{t("signalSources.guildId")}</label>
                <input
                  type="text"
                  value={guildId}
                  onChange={(e) => setGuildId(e.target.value)}
                  className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm font-mono"
                />
              </div>
            </>
          )}
          {/* Routing Mode */}
          <div>
            <label className="block text-sm text-muted-foreground mb-1">{t("signalSources.routingMode")}</label>
            <select
              value={routingMode}
              onChange={(e) => setRoutingMode(e.target.value as "GLOBAL" | "ASSIGNED")}
              className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm"
            >
              <option value="GLOBAL">{t("signalSources.routingGlobal")}</option>
              <option value="ASSIGNED">{t("signalSources.routingAssigned")}</option>
            </select>
            <p className="text-xs text-muted-foreground mt-1">
              {routingMode === "GLOBAL"
                ? t("signalSources.routingGlobalHint")
                : t("signalSources.routingAssignedHint")}
            </p>
          </div>
          {/* Trade Mode */}
          <div>
            <label className="block text-sm text-muted-foreground mb-1">{t("signalSources.tradeMode")}</label>
            <select
              value={tradeMode}
              onChange={(e) => setTradeMode(e.target.value as TradeMode)}
              className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm"
            >
              <option value="AUTO">{t("signalSources.tradeAuto")}</option>
              <option value="SHADOW">{t("signalSources.tradeShadow")}</option>
              <option value="MANUAL">{t("signalSources.tradeManual")}</option>
            </select>
            <p className="text-xs text-muted-foreground mt-1">
              {tradeMode === "AUTO"
                ? t("signalSources.tradeAutoHint")
                : tradeMode === "SHADOW"
                  ? t("signalSources.tradeShadowHint")
                  : t("signalSources.tradeManualHint")}
            </p>
          </div>
          {/* Risk Multiplier */}
          <div>
            <label className="block text-sm text-muted-foreground mb-1">{t("signalSources.riskMultiplier")}</label>
            <input
              type="number"
              value={riskMultiplier}
              onChange={(e) => setRiskMultiplier(parseFloat(e.target.value) || 1.0)}
              min={0.1}
              max={3.0}
              step={0.1}
              className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm font-mono"
            />
            <p className="text-xs text-muted-foreground mt-1">{t("signalSources.riskMultiplierHint")}</p>
          </div>
          <div>
            <label className="block text-sm text-muted-foreground mb-1">{t("signalSources.description")}</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
              className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm resize-none"
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm rounded-lg border border-border hover:bg-accent transition-colors"
            >
              {t("common.cancel")}
            </button>
            <button
              type="submit"
              disabled={saving || !name || !displayName}
              className="px-4 py-2 text-sm rounded-lg bg-purple-600 hover:bg-purple-700 text-white disabled:opacity-50 transition-colors"
            >
              {saving ? t("common.saving") : t("common.confirm")}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
