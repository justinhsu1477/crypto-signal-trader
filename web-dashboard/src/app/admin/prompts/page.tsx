"use client";

import { useEffect, useState, useCallback } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import {
  getAdminPromptVersions,
  createAdminPromptVersion,
  activateAdminPromptVersion,
} from "@/lib/api";
import type { PromptVersion } from "@/lib/api";
import {
  ScrollText,
  Plus,
  Zap,
  Eye,
  EyeOff,
  Loader2,
  X,
  Check,
} from "lucide-react";
import { toast } from "sonner";

export default function AdminPromptsPage() {
  const { t } = useT();
  const [versions, setVersions] = useState<PromptVersion[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [previewId, setPreviewId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [activating, setActivating] = useState<number | null>(null);

  // Create form
  const [newContent, setNewContent] = useState("");
  const [newDescription, setNewDescription] = useState("");

  const fetchData = useCallback(async () => {
    try {
      const data = await getAdminPromptVersions();
      setVersions(data);
    } catch {
      toast.error(t("common.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  async function handleCreate() {
    if (!newContent.trim()) return;
    setCreating(true);
    try {
      await createAdminPromptVersion(newContent, newDescription);
      toast.success(t("prompts.createSuccess"));
      setShowCreate(false);
      setNewContent("");
      setNewDescription("");
      fetchData();
    } catch {
      toast.error(t("common.loadFailed"));
    } finally {
      setCreating(false);
    }
  }

  async function handleActivate(id: number) {
    if (!confirm(t("prompts.activateConfirm"))) return;
    setActivating(id);
    try {
      await activateAdminPromptVersion(id);
      toast.success(t("prompts.activateSuccess"));
      fetchData();
    } catch {
      toast.error(t("common.loadFailed"));
    } finally {
      setActivating(null);
    }
  }

  const activeVersion = versions.find((v) => v.active);

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
        <h1 className="text-2xl font-bold flex items-center gap-2">
          <ScrollText className="h-6 w-6 text-purple-400" />
          {t("prompts.title")}
        </h1>
        <button
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-2 px-4 py-2 bg-purple-600 hover:bg-purple-700 text-white rounded-lg text-sm transition-colors"
        >
          <Plus className="h-4 w-4" />
          {t("prompts.createNew")}
        </button>
      </div>

      {/* Active Status */}
      <div className="bg-card border border-border rounded-xl p-4">
        {activeVersion ? (
          <div className="flex items-center gap-3">
            <Check className="h-5 w-5 text-green-400" />
            <span className="text-sm">
              <strong className="text-green-400">v{activeVersion.version}</strong>
              {" — "}
              {activeVersion.description || "No description"}
              <span className="text-muted-foreground ml-2">
                (~{activeVersion.tokenCount ?? "?"} tokens)
              </span>
            </span>
          </div>
        ) : (
          <div className="flex items-center gap-3 text-muted-foreground">
            <ScrollText className="h-5 w-5" />
            <span className="text-sm">{t("prompts.usingDefault")}</span>
          </div>
        )}
      </div>

      {/* Version List */}
      {versions.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <ScrollText className="h-12 w-12 mx-auto mb-3 opacity-30" />
          <p className="text-sm">{t("prompts.noVersions")}</p>
        </div>
      ) : (
        <div className="space-y-3">
          {versions.map((v) => (
            <div
              key={v.id}
              className={`bg-card border rounded-xl p-4 ${
                v.active ? "border-green-500/50" : "border-border"
              }`}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <span className="font-mono text-sm font-bold">v{v.version}</span>
                  {v.active && (
                    <span className="text-xs px-2 py-0.5 rounded bg-green-500/20 text-green-400">
                      {t("prompts.active")}
                    </span>
                  )}
                  <span className="text-sm text-muted-foreground">
                    {v.description || "—"}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground">
                    ~{v.tokenCount ?? "?"} tokens
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {new Date(v.createdAt).toLocaleDateString()}
                  </span>
                  <button
                    onClick={() => setPreviewId(previewId === v.id ? null : v.id)}
                    className="p-1.5 rounded hover:bg-accent transition-colors"
                    title={t("prompts.preview")}
                  >
                    {previewId === v.id ? (
                      <EyeOff className="h-4 w-4" />
                    ) : (
                      <Eye className="h-4 w-4" />
                    )}
                  </button>
                  {!v.active && (
                    <button
                      onClick={() => handleActivate(v.id)}
                      disabled={activating === v.id}
                      className="flex items-center gap-1 px-3 py-1 text-xs rounded-lg bg-purple-600 hover:bg-purple-700 text-white disabled:opacity-50 transition-colors"
                    >
                      {activating === v.id ? (
                        <Loader2 className="h-3 w-3 animate-spin" />
                      ) : (
                        <Zap className="h-3 w-3" />
                      )}
                      {t("prompts.activate")}
                    </button>
                  )}
                </div>
              </div>

              {/* Preview */}
              {previewId === v.id && (
                <pre className="mt-3 p-3 bg-background rounded-lg text-xs text-muted-foreground overflow-x-auto max-h-96 whitespace-pre-wrap">
                  {v.content}
                </pre>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Create Modal */}
      {showCreate && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
          onClick={() => setShowCreate(false)}
        >
          <div
            className="bg-card border border-border rounded-xl p-6 w-full max-w-3xl mx-4 max-h-[90vh] overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold">{t("prompts.createNew")}</h3>
              <button onClick={() => setShowCreate(false)} className="p-1 hover:bg-accent rounded">
                <X className="h-5 w-5" />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium mb-1">{t("prompts.description")}</label>
                <input
                  type="text"
                  value={newDescription}
                  onChange={(e) => setNewDescription(e.target.value)}
                  placeholder={t("prompts.descriptionPlaceholder")}
                  className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">{t("prompts.content")}</label>
                <textarea
                  value={newContent}
                  onChange={(e) => setNewContent(e.target.value)}
                  rows={20}
                  className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm font-mono"
                  placeholder="Paste your system prompt here..."
                />
                <p className="text-xs text-muted-foreground mt-1">
                  ~{Math.ceil(newContent.length / 3)} est. tokens ({newContent.length} chars)
                </p>
              </div>
              <div className="flex justify-end gap-2">
                <button
                  onClick={() => setShowCreate(false)}
                  className="px-4 py-2 text-sm rounded-lg border border-border hover:bg-accent transition-colors"
                >
                  {t("common.cancel")}
                </button>
                <button
                  onClick={handleCreate}
                  disabled={!newContent.trim() || creating}
                  className="px-4 py-2 text-sm rounded-lg bg-purple-600 hover:bg-purple-700 text-white disabled:opacity-50 transition-colors"
                >
                  {creating ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    t("common.save")
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
