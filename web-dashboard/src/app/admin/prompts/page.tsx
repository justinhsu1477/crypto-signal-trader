"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import {
  getAdminPromptVersions,
  createAdminPromptVersion,
  activateAdminPromptVersion,
} from "@/lib/api";
import type { PromptVersion } from "@/lib/api";
import { diffLines } from "diff";
import {
  ScrollText,
  Plus,
  Zap,
  Eye,
  EyeOff,
  Loader2,
  X,
  Check,
  GitCompare,
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
  const [baseVersionId, setBaseVersionId] = useState<number | "">("");
  const [showDiffInCreate, setShowDiffInCreate] = useState(false);

  // Compare modal
  const [diffFromId, setDiffFromId] = useState<number | "">("");
  const [diffToId, setDiffToId] = useState<number | "">("");
  const [showDiff, setShowDiff] = useState(false);

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

  const activeVersion = versions.find((v) => v.active);

  // 打開 Create modal 時，自動 pre-fill active version 的內容當 base
  // 避免 admin 從空白開始打字 — 90% 的情境是「微調現役 prompt」而非「整個重寫」
  function openCreate() {
    if (activeVersion) {
      setBaseVersionId(activeVersion.id);
      setNewContent(activeVersion.content);
      setNewDescription("");
    }
    setShowCreate(true);
  }

  function closeCreate() {
    setShowCreate(false);
    setNewContent("");
    setNewDescription("");
    setBaseVersionId("");
    setShowDiffInCreate(false);
  }

  // 切換 base 時把 content 替換成那個 base 的內容（admin 從一個底版開始改）。
  // 若 content 已經被 admin 編輯過（跟原 base 不同），confirm 確認再覆寫，避免丟失草稿。
  function changeBase(idStr: string) {
    if (idStr === "") {
      if (newContent.trim() && !confirm(t("prompts.baseChangeConfirm"))) return;
      setBaseVersionId("");
      setNewContent("");
      return;
    }
    const id = Number(idStr);
    const base = versions.find((v) => v.id === id);
    if (!base) return;
    // 若當前 content 跟「目前 base」的原內容不一致，代表 admin 改過 → confirm
    const currentBase = versions.find((v) => v.id === baseVersionId);
    const hasUnsavedEdits =
      currentBase != null && newContent !== currentBase.content;
    if (hasUnsavedEdits && !confirm(t("prompts.baseChangeConfirm"))) return;
    setBaseVersionId(id);
    setNewContent(base.content);
  }

  async function handleCreate() {
    if (!newContent.trim()) return;
    setCreating(true);
    try {
      await createAdminPromptVersion(newContent, newDescription);
      toast.success(t("prompts.createSuccess"));
      closeCreate();
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

  // 開 Compare modal — 預設 from = active, to = 點擊的版本
  function openDiff(toId: number) {
    if (!activeVersion || activeVersion.id === toId) {
      // 沒 active 或自己比自己 → 不開
      return;
    }
    setDiffFromId(activeVersion.id);
    setDiffToId(toId);
    setShowDiff(true);
  }

  function closeDiff() {
    setShowDiff(false);
    setDiffFromId("");
    setDiffToId("");
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
        <h1 className="text-2xl font-bold flex items-center gap-2">
          <ScrollText className="h-6 w-6 text-purple-400" />
          {t("prompts.title")}
        </h1>
        <button
          onClick={openCreate}
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
                  {/* Compare vs active — 只在非 active 版本 + 有 active 時顯示 */}
                  {!v.active && activeVersion && (
                    <button
                      onClick={() => openDiff(v.id)}
                      className="p-1.5 rounded hover:bg-accent transition-colors"
                      title={t("prompts.compareWithActive")}
                    >
                      <GitCompare className="h-4 w-4" />
                    </button>
                  )}
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
          onClick={closeCreate}
        >
          <div
            className="bg-card border border-border rounded-xl p-6 w-full max-w-4xl mx-4 max-h-[90vh] overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold">{t("prompts.createNew")}</h3>
              <button onClick={closeCreate} className="p-1 hover:bg-accent rounded">
                <X className="h-5 w-5" />
              </button>
            </div>

            <div className="space-y-4">
              {/* Base version 選擇 — pre-fill content */}
              <div>
                <label className="block text-sm font-medium mb-1">
                  {t("prompts.baseOn")}
                </label>
                <select
                  value={baseVersionId}
                  onChange={(e) => changeBase(e.target.value)}
                  className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm"
                >
                  <option value="">{t("prompts.baseOnNone")}</option>
                  {versions.map((v) => (
                    <option key={v.id} value={v.id}>
                      v{v.version}
                      {v.active ? " (active)" : ""}
                      {v.description ? ` — ${v.description}` : ""}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium mb-1">
                  {t("prompts.description")}
                </label>
                <input
                  type="text"
                  value={newDescription}
                  onChange={(e) => setNewDescription(e.target.value)}
                  placeholder={t("prompts.descriptionPlaceholder")}
                  className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm"
                />
              </div>

              <div>
                <div className="flex items-center justify-between mb-1">
                  <label className="text-sm font-medium">{t("prompts.content")}</label>
                  {baseVersionId !== "" && (
                    <button
                      onClick={() => setShowDiffInCreate(!showDiffInCreate)}
                      className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                    >
                      <GitCompare className="h-3 w-3" />
                      {showDiffInCreate
                        ? t("prompts.hideDiffPreview")
                        : t("prompts.showDiffPreview")}
                    </button>
                  )}
                </div>
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

              {/* Inline diff vs base */}
              {showDiffInCreate && baseVersionId !== "" && (
                <DiffPreview
                  fromContent={
                    versions.find((v) => v.id === baseVersionId)?.content ?? ""
                  }
                  toContent={newContent}
                  fromLabel={`v${versions.find((v) => v.id === baseVersionId)?.version ?? "?"}`}
                  toLabel={t("prompts.draftLabel")}
                />
              )}

              <div className="flex justify-end gap-2">
                <button
                  onClick={closeCreate}
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

      {/* Compare Modal */}
      {showDiff && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
          onClick={closeDiff}
        >
          <div
            className="bg-card border border-border rounded-xl p-6 w-full max-w-5xl mx-4 max-h-[90vh] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold flex items-center gap-2">
                <GitCompare className="h-5 w-5 text-purple-400" />
                {t("prompts.compareVersions")}
              </h3>
              <button onClick={closeDiff} className="p-1 hover:bg-accent rounded">
                <X className="h-5 w-5" />
              </button>
            </div>

            {/* From / To selectors */}
            <div className="grid grid-cols-2 gap-3 mb-4">
              <div>
                <label className="block text-xs text-muted-foreground mb-1">
                  {t("prompts.diffFrom")}
                </label>
                <select
                  value={diffFromId}
                  onChange={(e) => setDiffFromId(Number(e.target.value))}
                  className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm"
                >
                  {versions.map((v) => (
                    <option key={v.id} value={v.id}>
                      v{v.version}
                      {v.active ? " (active)" : ""}
                      {v.description ? ` — ${v.description}` : ""}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1">
                  {t("prompts.diffTo")}
                </label>
                <select
                  value={diffToId}
                  onChange={(e) => setDiffToId(Number(e.target.value))}
                  className="w-full bg-background border border-border rounded-lg px-3 py-2 text-sm"
                >
                  {versions.map((v) => (
                    <option key={v.id} value={v.id}>
                      v{v.version}
                      {v.active ? " (active)" : ""}
                      {v.description ? ` — ${v.description}` : ""}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            {diffFromId !== "" && diffToId !== "" && diffFromId !== diffToId ? (
              <div className="flex-1 overflow-hidden">
                <DiffPreview
                  fromContent={versions.find((v) => v.id === diffFromId)?.content ?? ""}
                  toContent={versions.find((v) => v.id === diffToId)?.content ?? ""}
                  fromLabel={`v${versions.find((v) => v.id === diffFromId)?.version ?? "?"}`}
                  toLabel={`v${versions.find((v) => v.id === diffToId)?.version ?? "?"}`}
                />
              </div>
            ) : (
              <div className="flex-1 flex items-center justify-center text-muted-foreground text-sm">
                {diffFromId === diffToId
                  ? t("prompts.diffSameVersion")
                  : t("prompts.diffSelectBoth")}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * 行級 diff 視覺化元件。
 *
 * 用 jsdiff `diffLines` 算兩份 prompt 的 line-level 差異，輸出按段（hunk）顯示：
 * - 新增：綠底 + `+` 前綴
 * - 移除：紅底 + `-` 前綴
 * - 不變：灰字 + ` ` 前綴（context）
 *
 * 摘要列在頂部顯示 +/- 行數與字元數，admin 快速判斷改動量級。
 */
function DiffPreview({
  fromContent,
  toContent,
  fromLabel,
  toLabel,
}: {
  fromContent: string;
  toContent: string;
  fromLabel: string;
  toLabel: string;
}) {
  const { t } = useT();

  const parts = useMemo(
    () => diffLines(fromContent, toContent, { newlineIsToken: false }),
    [fromContent, toContent],
  );

  const stats = useMemo(() => {
    let addedLines = 0;
    let removedLines = 0;
    let addedChars = 0;
    let removedChars = 0;
    for (const p of parts) {
      const lineCount = p.value.split("\n").filter((l) => l.length > 0).length;
      if (p.added) {
        addedLines += lineCount;
        addedChars += p.value.length;
      } else if (p.removed) {
        removedLines += lineCount;
        removedChars += p.value.length;
      }
    }
    return { addedLines, removedLines, addedChars, removedChars };
  }, [parts]);

  return (
    <div className="space-y-2 overflow-hidden flex flex-col h-full">
      {/* Stats header */}
      <div className="flex items-center gap-4 text-xs px-3 py-2 bg-background rounded-lg border border-border">
        <span className="text-muted-foreground">
          {fromLabel} → {toLabel}
        </span>
        <span className="text-green-400">
          +{stats.addedLines} {t("prompts.lines")} (+{stats.addedChars} {t("prompts.chars")})
        </span>
        <span className="text-red-400">
          -{stats.removedLines} {t("prompts.lines")} (-{stats.removedChars} {t("prompts.chars")})
        </span>
      </div>

      {/* Unified diff body */}
      <pre className="text-xs font-mono p-3 bg-background rounded-lg border border-border overflow-auto flex-1 max-h-[60vh] whitespace-pre-wrap leading-snug">
        {parts.map((p, i) => {
          if (p.added) {
            return (
              <span
                key={i}
                className="bg-green-500/15 text-green-300 block border-l-2 border-green-500/50 pl-2 -ml-2"
              >
                {prefixLines(p.value, "+ ")}
              </span>
            );
          }
          if (p.removed) {
            return (
              <span
                key={i}
                className="bg-red-500/15 text-red-300 block border-l-2 border-red-500/50 pl-2 -ml-2"
              >
                {prefixLines(p.value, "- ")}
              </span>
            );
          }
          return (
            <span key={i} className="text-muted-foreground block opacity-60">
              {prefixLines(p.value, "  ")}
            </span>
          );
        })}
      </pre>
    </div>
  );
}

/** 為每一行加上 prefix（+ / - /  ），保留 trailing newline。 */
function prefixLines(s: string, prefix: string): string {
  if (!s) return "";
  const trailing = s.endsWith("\n");
  const body = trailing ? s.slice(0, -1) : s;
  const prefixed = body
    .split("\n")
    .map((l) => prefix + l)
    .join("\n");
  return trailing ? prefixed + "\n" : prefixed;
}
