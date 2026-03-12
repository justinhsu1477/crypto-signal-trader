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
} from "@/lib/api";
import type {
  SignalSourceResponse,
  CreateSignalSourceRequest,
  UpdateSignalSourceRequest,
  UserAssignmentResponse,
  SignalSourcePerformanceDto,
  AdminUserListResponse,
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
} from "lucide-react";
import { toast } from "sonner";

export default function AdminSignalSourcesPage() {
  const { t } = useT();
  const [sources, setSources] = useState<SignalSourceResponse[]>([]);
  const [performances, setPerformances] = useState<SignalSourcePerformanceDto[]>([]);
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

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const [s, p] = await Promise.all([
        getAdminSignalSources(),
        getAdminSignalSourcePerformances(),
      ]);
      setSources(s);
      setPerformances(p);
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

                  {/* Assigned count badge */}
                  <div className="flex items-center gap-1 px-2 py-1 bg-accent rounded-md text-xs">
                    <Users className="h-3 w-3" />
                    <span>{source.assignedUserCount}</span>
                  </div>

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
                    <button
                      onClick={() => handleExpandUsers(source.id)}
                      className="p-1.5 rounded-md hover:bg-accent text-muted-foreground transition-colors"
                    >
                      {isExpanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                    </button>
                  </div>
                </div>

                {/* Expanded: assigned users */}
                {isExpanded && (
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
                  <th className="pb-2 pr-4">{t("signalSources.displayName")}</th>
                  <th className="pb-2 pr-4 text-right">{t("signalSources.trades")}</th>
                  <th className="pb-2 pr-4 text-right">{t("signalSources.winRate")}</th>
                  <th className="pb-2 pr-4 text-right">{t("signalSources.totalPnl")}</th>
                  <th className="pb-2 text-right">{t("signalSources.avgPnl")}</th>
                </tr>
              </thead>
              <tbody>
                {performances.map((p) => (
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
  const [saving, setSaving] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    try {
      if (isEdit && source) {
        const req: UpdateSignalSourceRequest = { name, displayName, description };
        await updateAdminSignalSource(source.id, req);
        toast.success(t("signalSources.updateSuccess"));
      } else {
        const req: CreateSignalSourceRequest = {
          name,
          displayName,
          channelId: channelId || undefined,
          guildId: guildId || undefined,
          description: description || undefined,
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
