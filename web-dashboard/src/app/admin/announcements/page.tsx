"use client";

import { useState, useMemo } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import {
  getAdminAnnouncements,
  createAnnouncement,
  updateAnnouncement,
  publishAnnouncement,
  archiveAnnouncement,
  deleteAnnouncement,
} from "@/lib/api";
import type { AnnouncementResponse, CreateAnnouncementRequest } from "@/types";
import {
  Megaphone,
  Plus,
  Send,
  Archive,
  Trash2,
  Pencil,
  X,
  FileText,
  Globe,
  AlertTriangle,
  ChevronUp,
  ChevronDown,
  ChevronsUpDown,
} from "lucide-react";
import { toast } from "sonner";
import useSWR from "swr";

type DialogMode = "create" | "edit" | null;

const CATEGORIES = ["GENERAL", "MAINTENANCE", "UPDATE", "URGENT", "PROMOTION"] as const;
const PRIORITIES = ["LOW", "NORMAL", "HIGH", "CRITICAL"] as const;
const CHANNEL_OPTIONS = ["ALL", "WEBSOCKET", "DISCORD", "LINE"] as const;

type AnnSortField = "title" | "category" | "priority" | "status" | "publishedAt" | "createdAt";
type SortDir = "asc" | "desc";

export default function AdminAnnouncementsPage() {
  const { t } = useT();
  const { data: announcements = [], isLoading: loading, mutate } = useSWR<AnnouncementResponse[]>(
    "admin-announcements",
    getAdminAnnouncements
  );
  const [dialogMode, setDialogMode] = useState<DialogMode>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sortField, setSortField] = useState<AnnSortField>("createdAt");
  const [sortDir, setSortDir] = useState<SortDir>("desc");

  // Form state
  const [formTitle, setFormTitle] = useState("");
  const [formContent, setFormContent] = useState("");
  const [formCategory, setFormCategory] = useState<string>("GENERAL");
  const [formPriority, setFormPriority] = useState<string>("NORMAL");
  const [formChannels, setFormChannels] = useState<string>("ALL");

  function resetForm() {
    setFormTitle("");
    setFormContent("");
    setFormCategory("GENERAL");
    setFormPriority("NORMAL");
    setFormChannels("ALL");
    setEditingId(null);
  }

  function openCreate() {
    resetForm();
    setDialogMode("create");
  }

  function openEdit(ann: AnnouncementResponse) {
    setFormTitle(ann.title);
    setFormContent(ann.content);
    setFormCategory(ann.category);
    setFormPriority(ann.priority);
    setFormChannels(ann.channels);
    setEditingId(ann.id);
    setDialogMode("edit");
  }

  function closeDialog() {
    setDialogMode(null);
    resetForm();
  }

  function buildRequest(): CreateAnnouncementRequest {
    return {
      title: formTitle,
      content: formContent,
      category: formCategory,
      priority: formPriority,
      channels: formChannels,
    };
  }

  async function handleSaveDraft() {
    setSubmitting(true);
    try {
      if (dialogMode === "edit" && editingId) {
        await updateAnnouncement(editingId, buildRequest());
        toast.success(t("announcement.updateSuccess"));
      } else {
        await createAnnouncement(buildRequest());
        toast.success(t("announcement.createSuccess"));
      }
      closeDialog();
      mutate();
    } catch {
      toast.error(t("announcement.operationFailed"));
    } finally {
      setSubmitting(false);
    }
  }

  async function handlePublishFromDialog() {
    setSubmitting(true);
    try {
      // Save first, then publish
      let saved: AnnouncementResponse;
      if (dialogMode === "edit" && editingId) {
        saved = await updateAnnouncement(editingId, buildRequest());
      } else {
        saved = await createAnnouncement(buildRequest());
      }
      await publishAnnouncement(saved.id);
      toast.success(t("announcement.publishSuccess"));
      closeDialog();
      mutate();
    } catch {
      toast.error(t("announcement.operationFailed"));
    } finally {
      setSubmitting(false);
    }
  }

  async function handlePublish(id: number) {
    if (!confirm(t("announcement.confirmPublish"))) return;
    try {
      await publishAnnouncement(id);
      toast.success(t("announcement.publishSuccess"));
      mutate();
    } catch {
      toast.error(t("announcement.operationFailed"));
    }
  }

  async function handleArchive(id: number) {
    if (!confirm(t("announcement.confirmArchive"))) return;
    try {
      await archiveAnnouncement(id);
      toast.success(t("announcement.archiveSuccess"));
      mutate();
    } catch {
      toast.error(t("announcement.operationFailed"));
    }
  }

  async function handleDelete(id: number) {
    if (!confirm(t("announcement.confirmDelete"))) return;
    try {
      await deleteAnnouncement(id);
      toast.success(t("announcement.deleteSuccess"));
      mutate();
    } catch {
      toast.error(t("announcement.operationFailed"));
    }
  }

  function categoryLabel(cat: string) {
    const map: Record<string, string> = {
      GENERAL: t("announcement.catGeneral"),
      MAINTENANCE: t("announcement.catMaintenance"),
      UPDATE: t("announcement.catUpdate"),
      URGENT: t("announcement.catUrgent"),
      PROMOTION: t("announcement.catPromotion"),
    };
    return map[cat] || cat;
  }

  function priorityLabel(pri: string) {
    const map: Record<string, string> = {
      LOW: t("announcement.priLow"),
      NORMAL: t("announcement.priNormal"),
      HIGH: t("announcement.priHigh"),
      CRITICAL: t("announcement.priCritical"),
    };
    return map[pri] || pri;
  }

  function statusLabel(status: string) {
    const map: Record<string, string> = {
      DRAFT: t("announcement.statusDraft"),
      PUBLISHED: t("announcement.statusPublished"),
      ARCHIVED: t("announcement.statusArchived"),
    };
    return map[status] || status;
  }

  function categoryBadge(cat: string) {
    const colors: Record<string, string> = {
      GENERAL: "bg-blue-500/20 text-blue-400",
      MAINTENANCE: "bg-yellow-500/20 text-yellow-400",
      UPDATE: "bg-green-500/20 text-green-400",
      URGENT: "bg-red-500/20 text-red-400",
      PROMOTION: "bg-purple-500/20 text-purple-400",
    };
    return (
      <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${colors[cat] || "bg-gray-500/20 text-gray-400"}`}>
        {categoryLabel(cat)}
      </span>
    );
  }

  function priorityBadge(pri: string) {
    const colors: Record<string, string> = {
      LOW: "bg-gray-500/20 text-gray-400",
      NORMAL: "bg-blue-500/20 text-blue-400",
      HIGH: "bg-orange-500/20 text-orange-400",
      CRITICAL: "bg-red-500/20 text-red-400",
    };
    return (
      <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${colors[pri] || "bg-gray-500/20 text-gray-400"}`}>
        {priorityLabel(pri)}
      </span>
    );
  }

  function statusBadge(status: string) {
    const colors: Record<string, string> = {
      DRAFT: "bg-gray-500/20 text-gray-400",
      PUBLISHED: "bg-green-500/20 text-green-400",
      ARCHIVED: "bg-yellow-500/20 text-yellow-400",
    };
    return (
      <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${colors[status] || "bg-gray-500/20 text-gray-400"}`}>
        {statusLabel(status)}
      </span>
    );
  }

  function toggleAnnSort(field: AnnSortField) {
    if (sortField === field) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortField(field);
      setSortDir(["createdAt", "publishedAt"].includes(field) ? "desc" : "asc");
    }
  }

  const sortedAnnouncements = useMemo(() => {
    return [...announcements].sort((a, b) => {
      const dir = sortDir === "asc" ? 1 : -1;
      const av = a[sortField];
      const bv = b[sortField];
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      return String(av).localeCompare(String(bv)) * dir;
    });
  }, [announcements, sortField, sortDir]);

  function AnnSortIcon({ field }: { field: AnnSortField }) {
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

  // Stats
  const drafts = announcements.filter((a) => a.status === "DRAFT").length;
  const published = announcements.filter((a) => a.status === "PUBLISHED").length;
  const archived = announcements.filter((a) => a.status === "ARCHIVED").length;

  const stats = [
    { label: t("announcement.statusDraft"), value: drafts, icon: FileText, color: "text-gray-400" },
    { label: t("announcement.statusPublished"), value: published, icon: Globe, color: "text-green-400" },
    { label: t("announcement.statusArchived"), value: archived, icon: Archive, color: "text-yellow-400" },
  ];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t("announcement.management")}</h1>
        <button
          onClick={openCreate}
          className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-purple-600 hover:bg-purple-700 text-white text-sm font-medium transition-colors"
        >
          <Plus className="h-4 w-4" />
          {t("announcement.create")}
        </button>
      </div>

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

      {/* Table */}
      <div className="rounded-xl border border-border bg-card">
        {sortedAnnouncements.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
            <Megaphone className="h-10 w-10 mb-3 opacity-40" />
            <p>{t("announcement.noAnnouncements")}</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-muted-foreground">
                  {([
                    { field: "title" as AnnSortField, label: t("announcement.title"), align: "text-left" },
                    { field: "category" as AnnSortField, label: t("announcement.category"), align: "text-center" },
                    { field: "priority" as AnnSortField, label: t("announcement.priority"), align: "text-center" },
                    { field: "status" as AnnSortField, label: t("announcement.status"), align: "text-center" },
                  ]).map((col) => (
                    <th
                      key={col.field}
                      onClick={() => toggleAnnSort(col.field)}
                      className={`${col.align} px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground transition-colors`}
                    >
                      <span className="inline-flex items-center gap-1">
                        {col.label}
                        <AnnSortIcon field={col.field} />
                      </span>
                    </th>
                  ))}
                  <th className="text-center px-4 py-3 font-medium">{t("announcement.channels")}</th>
                  <th
                    onClick={() => toggleAnnSort("publishedAt")}
                    className="text-left px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground transition-colors"
                  >
                    <span className="inline-flex items-center gap-1">
                      {t("announcement.publishedAt")}
                      <AnnSortIcon field="publishedAt" />
                    </span>
                  </th>
                  <th className="text-center px-4 py-3 font-medium">{t("announcement.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {sortedAnnouncements.map((ann) => (
                  <tr
                    key={ann.id}
                    className="border-b border-border/50 hover:bg-accent/30 transition-colors"
                  >
                    <td className="px-4 py-3">
                      <div className="max-w-[240px]">
                        <div className="font-medium truncate">{ann.title}</div>
                        <div className="text-xs text-muted-foreground truncate mt-0.5">{ann.content}</div>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-center">{categoryBadge(ann.category)}</td>
                    <td className="px-4 py-3 text-center">{priorityBadge(ann.priority)}</td>
                    <td className="px-4 py-3 text-center">{statusBadge(ann.status)}</td>
                    <td className="px-4 py-3 text-center">
                      <span className="text-xs text-muted-foreground">{ann.channels}</span>
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {ann.publishedAt
                        ? new Date(ann.publishedAt).toLocaleString()
                        : "-"}
                    </td>
                    <td className="px-4 py-3 text-center">
                      <div className="flex items-center justify-center gap-1">
                        {ann.status === "DRAFT" && (
                          <>
                            <button
                              onClick={() => openEdit(ann)}
                              title={t("announcement.edit")}
                              className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
                            >
                              <Pencil className="h-4 w-4" />
                            </button>
                            <button
                              onClick={() => handlePublish(ann.id)}
                              title={t("announcement.publish")}
                              className="p-1.5 rounded-lg hover:bg-green-500/20 text-muted-foreground hover:text-green-400 transition-colors"
                            >
                              <Send className="h-4 w-4" />
                            </button>
                            <button
                              onClick={() => handleDelete(ann.id)}
                              title={t("announcement.deleteBtn")}
                              className="p-1.5 rounded-lg hover:bg-red-500/20 text-muted-foreground hover:text-red-400 transition-colors"
                            >
                              <Trash2 className="h-4 w-4" />
                            </button>
                          </>
                        )}
                        {ann.status === "PUBLISHED" && (
                          <button
                            onClick={() => handleArchive(ann.id)}
                            title={t("announcement.archive")}
                            className="p-1.5 rounded-lg hover:bg-yellow-500/20 text-muted-foreground hover:text-yellow-400 transition-colors"
                          >
                            <Archive className="h-4 w-4" />
                          </button>
                        )}
                        {ann.status === "ARCHIVED" && (
                          <span className="text-xs text-muted-foreground">-</span>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Create/Edit Dialog */}
      {dialogMode && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
          <div className="bg-card border border-border rounded-2xl shadow-xl w-full max-w-lg mx-4 p-6 space-y-5">
            {/* Dialog Header */}
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-bold">
                {dialogMode === "create" ? t("announcement.create") : t("announcement.edit")}
              </h2>
              <button
                onClick={closeDialog}
                className="p-1 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {/* Title */}
            <div>
              <label className="block text-sm font-medium mb-1">{t("announcement.title")}</label>
              <input
                type="text"
                value={formTitle}
                onChange={(e) => setFormTitle(e.target.value)}
                placeholder={t("announcement.titlePlaceholder")}
                maxLength={200}
                className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-purple-500/50"
              />
            </div>

            {/* Content */}
            <div>
              <label className="block text-sm font-medium mb-1">{t("announcement.content")}</label>
              <textarea
                value={formContent}
                onChange={(e) => setFormContent(e.target.value)}
                placeholder={t("announcement.contentPlaceholder")}
                rows={4}
                className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-purple-500/50 resize-none"
              />
            </div>

            {/* Category + Priority row */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1">{t("announcement.category")}</label>
                <select
                  value={formCategory}
                  onChange={(e) => setFormCategory(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-purple-500/50"
                >
                  {CATEGORIES.map((c) => (
                    <option key={c} value={c}>{categoryLabel(c)}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">{t("announcement.priority")}</label>
                <select
                  value={formPriority}
                  onChange={(e) => setFormPriority(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-purple-500/50"
                >
                  {PRIORITIES.map((p) => (
                    <option key={p} value={p}>{priorityLabel(p)}</option>
                  ))}
                </select>
              </div>
            </div>

            {/* Channels */}
            <div>
              <label className="block text-sm font-medium mb-1">{t("announcement.channels")}</label>
              <div className="flex flex-wrap gap-2">
                {CHANNEL_OPTIONS.map((ch) => {
                  const selected =
                    formChannels === "ALL"
                      ? ch === "ALL"
                      : formChannels.split(",").includes(ch);
                  return (
                    <button
                      key={ch}
                      type="button"
                      onClick={() => {
                        if (ch === "ALL") {
                          setFormChannels("ALL");
                        } else {
                          // Toggle individual channel
                          const current = formChannels === "ALL" ? [] : formChannels.split(",").filter(Boolean);
                          const filtered = current.filter((c) => c !== "ALL");
                          if (filtered.includes(ch)) {
                            const next = filtered.filter((c) => c !== ch);
                            setFormChannels(next.length === 0 ? "ALL" : next.join(","));
                          } else {
                            const next = [...filtered, ch];
                            setFormChannels(next.join(","));
                          }
                        }
                      }}
                      className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors ${
                        selected
                          ? "bg-purple-600/20 border-purple-500 text-purple-400"
                          : "bg-background border-border text-muted-foreground hover:border-purple-500/50"
                      }`}
                    >
                      {ch === "ALL"
                        ? t("announcement.channelAll")
                        : ch === "WEBSOCKET"
                          ? t("announcement.channelWebSocket")
                          : ch === "DISCORD"
                            ? t("announcement.channelDiscord")
                            : t("announcement.channelLine")}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Warning for URGENT/CRITICAL */}
            {(formCategory === "URGENT" || formPriority === "CRITICAL") && (
              <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-red-500/10 border border-red-500/30 text-red-400 text-xs">
                <AlertTriangle className="h-4 w-4 shrink-0" />
                <span>{t("announcement.confirmPublish")}</span>
              </div>
            )}

            {/* Buttons */}
            <div className="flex items-center justify-end gap-2 pt-2">
              <button
                onClick={closeDialog}
                disabled={submitting}
                className="px-4 py-2 rounded-lg border border-border text-sm text-muted-foreground hover:text-foreground hover:bg-accent transition-colors disabled:opacity-50"
              >
                {t("common.cancel")}
              </button>
              <button
                onClick={handleSaveDraft}
                disabled={submitting || !formTitle.trim() || !formContent.trim()}
                className="px-4 py-2 rounded-lg border border-border bg-accent/50 text-sm font-medium hover:bg-accent transition-colors disabled:opacity-50"
              >
                {t("announcement.saveDraft")}
              </button>
              <button
                onClick={handlePublishFromDialog}
                disabled={submitting || !formTitle.trim() || !formContent.trim()}
                className="px-4 py-2 rounded-lg bg-green-600 hover:bg-green-700 text-white text-sm font-medium transition-colors disabled:opacity-50"
              >
                {t("announcement.publishNow")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
