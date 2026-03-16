"use client";

import { useEffect, useState } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAdminDailyReports, getAdminDailyReportDetail, generateAdminDailyReport } from "@/lib/api";
import type { DailySignalReportSummary, DailySignalReportDetail } from "@/types";
import { ChevronLeft, ChevronRight, ChevronDown, ChevronUp, Plus, Loader2 } from "lucide-react";
import { toast } from "sonner";

interface SourceStat {
  source: string;
  count: number;
  long: number;
  short: number;
  entries: number;
  avgConfidence: number | null;
  actions: Record<string, number>;
}

interface SymbolStat {
  symbol: string;
  count: number;
}

export default function DailyReportsPage() {
  const { t } = useT();
  const [reports, setReports] = useState<DailySignalReportSummary[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<DailySignalReportDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [generateDate, setGenerateDate] = useState("");
  const [generating, setGenerating] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getAdminDailyReports(page, 20)
      .then((res) => {
        if (!cancelled) {
          setReports(res.content);
          setTotalPages(res.totalPages);
          setTotalElements(res.totalElements);
        }
      })
      .catch(() => {
        if (!cancelled) setReports([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [page]);

  function toggleExpand(id: number) {
    if (expandedId === id) {
      setExpandedId(null);
      setDetail(null);
      return;
    }
    setExpandedId(id);
    setDetailLoading(true);
    getAdminDailyReportDetail(id)
      .then(setDetail)
      .catch(() => setDetail(null))
      .finally(() => setDetailLoading(false));
  }

  async function handleGenerate() {
    if (!generateDate || generating) return;
    setGenerating(true);
    try {
      const result = await generateAdminDailyReport(generateDate);
      toast.success(`日報已產生：${generateDate}（${result.totalSignals} 條訊號）`);
      // Refresh list
      setPage(0);
      const res = await getAdminDailyReports(0, 20);
      setReports(res.content);
      setTotalPages(res.totalPages);
      setTotalElements(res.totalElements);
      setGenerateDate("");
    } catch (err) {
      toast.error(`產生日報失敗：${err instanceof Error ? err.message : "未知錯誤"}`);
    } finally {
      setGenerating(false);
    }
  }

  function formatDate(dateStr: string) {
    return dateStr; // Already YYYY-MM-DD format
  }

  function parseReportData(jsonStr: string): { sourceStats: SourceStat[]; symbolStats: SymbolStat[]; actionDistribution: Record<string, number> } | null {
    try {
      return JSON.parse(jsonStr);
    } catch {
      return null;
    }
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
      {/* Header */}
      <div className="flex items-center justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-2xl font-bold">{t("admin.dailyReportTitle")}</h1>
          <span className="text-sm text-muted-foreground">
            {totalElements} {t("admin.rows")}
          </span>
        </div>
        {/* Manual Generate */}
        <div className="flex items-center gap-2">
          <input
            type="date"
            value={generateDate}
            onChange={(e) => setGenerateDate(e.target.value)}
            className="px-3 py-1.5 text-sm rounded-lg border border-border bg-background"
          />
          <button
            onClick={handleGenerate}
            disabled={!generateDate || generating}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {generating ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Plus className="h-4 w-4" />
            )}
            {generating ? t("admin.dailyReportGenerating") : t("admin.dailyReportGenerate")}
          </button>
        </div>
      </div>

      {/* Table */}
      <div className="rounded-xl border border-border bg-card">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="text-left px-4 py-3 font-medium w-8"></th>
                <th className="text-left px-4 py-3 font-medium">{t("admin.dailyReportDate")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.dailyReportSignals")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.dailyReportSources")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.dailyReportLong")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.dailyReportShort")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.dailyReportConfidence")}</th>
                <th className="text-center px-4 py-3 font-medium">{t("admin.dailyReportAiAnalysis")}</th>
              </tr>
            </thead>
            <tbody>
              {reports.map((report) => (
                <>
                  <tr
                    key={report.id}
                    onClick={() => toggleExpand(report.id)}
                    className="border-b border-border/50 hover:bg-accent/30 transition-colors cursor-pointer"
                  >
                    <td className="px-4 py-3">
                      {expandedId === report.id ? (
                        <ChevronUp className="h-4 w-4 text-muted-foreground" />
                      ) : (
                        <ChevronDown className="h-4 w-4 text-muted-foreground" />
                      )}
                    </td>
                    <td className="px-4 py-3 font-mono">{formatDate(report.reportDate)}</td>
                    <td className="px-4 py-3 text-right font-medium">{report.totalSignals}</td>
                    <td className="px-4 py-3 text-right">{report.totalSources}</td>
                    <td className="px-4 py-3 text-right text-green-500">{report.longCount}</td>
                    <td className="px-4 py-3 text-right text-red-500">{report.shortCount}</td>
                    <td className="px-4 py-3 text-right">
                      {report.avgConfidence != null ? `${report.avgConfidence}` : "-"}
                    </td>
                    <td className="px-4 py-3 text-center">
                      {report.hasAiAnalysis ? (
                        <span className="inline-block h-2 w-2 rounded-full bg-green-500" />
                      ) : (
                        <span className="inline-block h-2 w-2 rounded-full bg-muted-foreground/30" />
                      )}
                    </td>
                  </tr>
                  {expandedId === report.id && (
                    <tr key={`detail-${report.id}`} className="border-b border-border/50">
                      <td colSpan={8} className="px-6 py-4 bg-accent/10">
                        {detailLoading ? (
                          <div className="flex items-center gap-2 text-muted-foreground text-sm">
                            <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-primary" />
                            Loading...
                          </div>
                        ) : detail ? (
                          <DetailView detail={detail} t={t} parseReportData={parseReportData} />
                        ) : (
                          <span className="text-muted-foreground text-sm">Failed to load details</span>
                        )}
                      </td>
                    </tr>
                  )}
                </>
              ))}
              {reports.length === 0 && (
                <tr>
                  <td colSpan={8} className="px-4 py-8 text-center text-muted-foreground">
                    {t("admin.dailyReportNoReports")}
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
                onClick={() => { setLoading(true); setPage((p) => Math.max(0, p - 1)); }}
                disabled={page === 0}
                className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors disabled:opacity-30"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              <button
                onClick={() => { setLoading(true); setPage((p) => Math.min(totalPages - 1, p + 1)); }}
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

function DetailView({
  detail,
  t,
  parseReportData,
}: {
  detail: DailySignalReportDetail;
  t: (key: string) => string;
  parseReportData: (json: string) => { sourceStats: SourceStat[]; symbolStats: SymbolStat[]; actionDistribution: Record<string, number> } | null;
}) {
  const data = parseReportData(detail.reportData);

  return (
    <div className="space-y-4">
      {/* Source Stats */}
      {data?.sourceStats && data.sourceStats.length > 0 && (
        <div>
          <h4 className="text-xs font-medium mb-2 text-muted-foreground uppercase tracking-wider">
            {t("admin.dailyReportSource")} ({data.sourceStats.length})
          </h4>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
            {data.sourceStats.map((s) => (
              <div key={s.source} className="rounded-lg border border-border/50 px-3 py-2 text-xs">
                <div className="font-medium mb-1">{s.source}</div>
                <div className="flex gap-3 text-muted-foreground">
                  <span>{s.count} signals</span>
                  <span className="text-green-500">{s.long}L</span>
                  <span className="text-red-500">{s.short}S</span>
                  {s.avgConfidence != null && <span>AI: {s.avgConfidence}</span>}
                </div>
                {s.actions && Object.keys(s.actions).length > 0 && (
                  <div className="mt-1 text-muted-foreground">
                    {Object.entries(s.actions).map(([action, count]) => (
                      <span key={action} className="mr-2">{action}:{count}</span>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Top Symbols */}
      {data?.symbolStats && data.symbolStats.length > 0 && (
        <div>
          <h4 className="text-xs font-medium mb-2 text-muted-foreground uppercase tracking-wider">
            {t("admin.dailyReportTopSymbols")}
          </h4>
          <div className="flex flex-wrap gap-2">
            {data.symbolStats.map((s) => (
              <span key={s.symbol} className="inline-flex items-center gap-1 px-2 py-1 rounded-md bg-accent/50 text-xs font-mono">
                {s.symbol} <span className="text-muted-foreground">({s.count})</span>
              </span>
            ))}
          </div>
        </div>
      )}

      {/* AI Analysis */}
      {detail.aiAnalysis ? (
        <div>
          <h4 className="text-xs font-medium mb-2 text-muted-foreground uppercase tracking-wider">
            {t("admin.dailyReportAiAnalysis")}
            {detail.aiTokensUsed != null && detail.aiTokensUsed > 0 && (
              <span className="ml-2 font-normal">({detail.aiTokensUsed} tokens)</span>
            )}
          </h4>
          <div className="rounded-lg border border-border/50 px-4 py-3 text-sm whitespace-pre-wrap leading-relaxed">
            {detail.aiAnalysis}
          </div>
        </div>
      ) : detail.totalSignals === 0 ? (
        <div className="text-sm text-muted-foreground">{t("admin.dailyReportNoSignals")}</div>
      ) : null}
    </div>
  );
}
