"use client";

import { useEffect, useState } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAdminBroadcastLogs, getAdminBroadcastLogDetail } from "@/lib/api";
import type { BroadcastLogSummary, BroadcastLogDetail } from "@/types";
import { ChevronLeft, ChevronRight, ChevronDown, ChevronUp } from "lucide-react";

export default function BroadcastLogsPage() {
  const { t } = useT();
  const [logs, setLogs] = useState<BroadcastLogSummary[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<BroadcastLogDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    getAdminBroadcastLogs(page, 20)
      .then((res) => {
        setLogs(res.content);
        setTotalPages(res.totalPages);
        setTotalElements(res.totalElements);
      })
      .catch(() => {
        setLogs([]);
      })
      .finally(() => setLoading(false));
  }, [page]);

  function toggleExpand(id: number) {
    if (expandedId === id) {
      setExpandedId(null);
      setDetail(null);
      return;
    }
    setExpandedId(id);
    setDetailLoading(true);
    getAdminBroadcastLogDetail(id)
      .then(setDetail)
      .catch(() => setDetail(null))
      .finally(() => setDetailLoading(false));
  }

  function formatDuration(ms: number | null) {
    if (ms == null) return "-";
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  }

  function formatTime(iso: string) {
    const d = new Date(iso);
    return d.toLocaleString("zh-TW", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  }

  function statusColor(log: BroadcastLogSummary) {
    if (log.failCount === 0 && log.successCount > 0) return "text-green-500";
    if (log.failCount > 0 && log.successCount === 0) return "text-red-500";
    if (log.failCount > 0) return "text-yellow-500";
    return "text-muted-foreground";
  }

  if (loading) {
    return (
      <div className="flex h-[60vh] items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t("admin.broadcastLogs")}</h1>
        <span className="text-sm text-muted-foreground">
          {totalElements} {t("admin.rows")}
        </span>
      </div>

      <div className="rounded-xl border border-border bg-card">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="text-left px-4 py-3 font-medium w-8"></th>
                <th className="text-left px-4 py-3 font-medium">{t("admin.broadcastTime")}</th>
                <th className="text-left px-4 py-3 font-medium">{t("admin.broadcastAction")}</th>
                <th className="text-left px-4 py-3 font-medium">{t("admin.broadcastSymbol")}</th>
                <th className="text-left px-4 py-3 font-medium">{t("admin.broadcastSide")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.broadcastSuccess")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.broadcastFail")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.broadcastSkipped")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.broadcastDuration")}</th>
              </tr>
            </thead>
            <tbody>
              {logs.map((log) => (
                <>
                  <tr
                    key={log.id}
                    onClick={() => toggleExpand(log.id)}
                    className="border-b border-border/50 hover:bg-accent/30 transition-colors cursor-pointer"
                  >
                    <td className="px-4 py-3">
                      {expandedId === log.id ? (
                        <ChevronUp className="h-4 w-4 text-muted-foreground" />
                      ) : (
                        <ChevronDown className="h-4 w-4 text-muted-foreground" />
                      )}
                    </td>
                    <td className="px-4 py-3 font-mono text-xs">{formatTime(log.createdAt)}</td>
                    <td className="px-4 py-3">
                      <span className="inline-flex items-center gap-1.5">
                        <span className={`font-medium ${statusColor(log)}`}>
                          {log.signalAction}
                        </span>
                        {log.aiConfidence != null && (
                          <span className="text-xs text-muted-foreground">AI:{log.aiConfidence}</span>
                        )}
                      </span>
                    </td>
                    <td className="px-4 py-3 font-mono">{log.symbol}</td>
                    <td className="px-4 py-3">{log.side || "-"}</td>
                    <td className="px-4 py-3 text-right text-green-500 font-medium">{log.successCount}</td>
                    <td className={`px-4 py-3 text-right font-medium ${log.failCount > 0 ? "text-red-500" : "text-muted-foreground"}`}>
                      {log.failCount}
                    </td>
                    <td className="px-4 py-3 text-right text-muted-foreground">
                      {log.skippedNoSub + log.skippedNoKey > 0
                        ? `${log.skippedNoSub + log.skippedNoKey}`
                        : "-"}
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-xs">
                      {formatDuration(log.durationMs)}
                    </td>
                  </tr>
                  {expandedId === log.id && (
                    <tr key={`detail-${log.id}`} className="border-b border-border/50">
                      <td colSpan={9} className="px-6 py-4 bg-accent/10">
                        {detailLoading ? (
                          <div className="flex items-center gap-2 text-muted-foreground text-sm">
                            <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-primary" />
                            Loading...
                          </div>
                        ) : detail ? (
                          <div className="space-y-3">
                            {/* Signal Details */}
                            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
                              {detail.entryPrice && (
                                <div><span className="text-muted-foreground">Entry:</span> {detail.entryPrice}</div>
                              )}
                              {detail.stopLoss && (
                                <div><span className="text-muted-foreground">SL:</span> {detail.stopLoss}</div>
                              )}
                              {detail.takeProfit && (
                                <div><span className="text-muted-foreground">TP:</span> {detail.takeProfit}</div>
                              )}
                              {detail.closeRatio != null && (
                                <div><span className="text-muted-foreground">Close:</span> {(detail.closeRatio * 100).toFixed(0)}%</div>
                              )}
                              {detail.isDca && (
                                <div><span className="text-muted-foreground">DCA</span></div>
                              )}
                              {detail.sourceAuthor && (
                                <div><span className="text-muted-foreground">Source:</span> {detail.sourceAuthor}</div>
                              )}
                              {detail.aiConfidence != null && (
                                <div><span className="text-muted-foreground">AI:</span> {detail.aiConfidence}/100</div>
                              )}
                            </div>
                            {detail.aiReasoning && (
                              <p className="text-xs text-muted-foreground">{detail.aiReasoning}</p>
                            )}

                            {/* Skipped breakdown */}
                            {(detail.skippedNoSub > 0 || detail.skippedNoKey > 0) && (
                              <div className="flex gap-4 text-xs text-muted-foreground">
                                {detail.skippedNoSub > 0 && (
                                  <span>{t("admin.broadcastNoSub")}: {detail.skippedNoSub}</span>
                                )}
                                {detail.skippedNoKey > 0 && (
                                  <span>{t("admin.broadcastNoKey")}: {detail.skippedNoKey}</span>
                                )}
                              </div>
                            )}

                            {/* User Results */}
                            {detail.userResults && detail.userResults.length > 0 && (
                              <div>
                                <h4 className="text-xs font-medium mb-2 text-muted-foreground">{t("admin.broadcastUserResults")}</h4>
                                <div className="space-y-1">
                                  {detail.userResults.map((ur, i) => (
                                    <div key={i} className="flex items-center gap-2 text-xs">
                                      <span className={`inline-block h-2 w-2 rounded-full ${ur.success ? "bg-green-500" : "bg-red-500"}`} />
                                      <span className="font-mono">{ur.email}</span>
                                      {ur.errorMessage && (
                                        <span className="text-red-400 truncate max-w-[300px]">{ur.errorMessage}</span>
                                      )}
                                    </div>
                                  ))}
                                </div>
                              </div>
                            )}
                          </div>
                        ) : (
                          <span className="text-muted-foreground text-sm">Failed to load details</span>
                        )}
                      </td>
                    </tr>
                  )}
                </>
              ))}
              {logs.length === 0 && (
                <tr>
                  <td colSpan={9} className="px-4 py-8 text-center text-muted-foreground">
                    {t("admin.broadcastNoLogs")}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-border">
            <span className="text-sm text-muted-foreground">
              {page + 1} / {totalPages}
            </span>
            <div className="flex gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-30"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              <button
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-30"
              >
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
