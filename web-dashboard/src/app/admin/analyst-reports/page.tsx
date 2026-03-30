"use client";

import { useEffect, useState } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAnalystReports, generateAnalystReport, getAnalystMessages } from "@/lib/api";
import type { AnalystReportSummary, AnalystMessageSummary } from "@/types";
import { ChevronLeft, ChevronRight, ChevronDown, ChevronUp, Plus, Loader2, MessageSquare } from "lucide-react";
import { toast } from "sonner";

export default function AnalystReportsPage() {
  const { t } = useT();
  const [reports, setReports] = useState<AnalystReportSummary[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [expandedMessages, setExpandedMessages] = useState<AnalystMessageSummary[] | null>(null);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [generateDate, setGenerateDate] = useState("");
  const [generating, setGenerating] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getAnalystReports(page, 20)
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

  function toggleExpand(report: AnalystReportSummary) {
    if (expandedId === report.id) {
      setExpandedId(null);
      setExpandedMessages(null);
      return;
    }
    setExpandedId(report.id);
    setMessagesLoading(true);
    getAnalystMessages(report.reportDate)
      .then(setExpandedMessages)
      .catch(() => setExpandedMessages(null))
      .finally(() => setMessagesLoading(false));
  }

  async function handleGenerate() {
    if (!generateDate || generating) return;
    setGenerating(true);
    try {
      const result = await generateAnalystReport(generateDate);
      toast.success(`${t("admin.analystReportTitle")}：${result.reportDate}（${result.analystCount} ${t("admin.analystReportAnalysts")}）`);
      setPage(0);
      const res = await getAnalystReports(0, 20);
      setReports(res.content);
      setTotalPages(res.totalPages);
      setTotalElements(res.totalElements);
      setGenerateDate("");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setGenerating(false);
    }
  }

  function parseReportData(jsonStr: string | null): { analysts: { analystName: string; channelId: string; messageCount: number; contentLength: number }[]; totalMessages: number } | null {
    if (!jsonStr) return null;
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
          <h1 className="text-2xl font-bold">{t("admin.analystReportTitle")}</h1>
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
            {generating ? t("admin.analystReportGenerating") : t("admin.analystReportGenerate")}
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
                <th className="text-left px-4 py-3 font-medium">{t("admin.analystReportDate")}</th>
                <th className="text-right px-4 py-3 font-medium">{t("admin.analystReportAnalysts")}</th>
                <th className="text-center px-4 py-3 font-medium">{t("admin.analystReportHasContent")}</th>
                <th className="text-right px-4 py-3 font-medium">Created</th>
              </tr>
            </thead>
            <tbody>
              {reports.map((report) => (
                <>
                  <tr
                    key={report.id}
                    onClick={() => toggleExpand(report)}
                    className="border-b border-border/50 hover:bg-accent/30 transition-colors cursor-pointer"
                  >
                    <td className="px-4 py-3">
                      {expandedId === report.id ? (
                        <ChevronUp className="h-4 w-4 text-muted-foreground" />
                      ) : (
                        <ChevronDown className="h-4 w-4 text-muted-foreground" />
                      )}
                    </td>
                    <td className="px-4 py-3 font-mono">{report.reportDate}</td>
                    <td className="px-4 py-3 text-right font-medium">{report.analystCount}</td>
                    <td className="px-4 py-3 text-center">
                      {report.reportContent ? (
                        <span className="inline-block h-2 w-2 rounded-full bg-green-500" />
                      ) : (
                        <span className="inline-block h-2 w-2 rounded-full bg-muted-foreground/30" />
                      )}
                    </td>
                    <td className="px-4 py-3 text-right text-muted-foreground text-xs">
                      {report.createdAt?.split("T")[0]}
                    </td>
                  </tr>
                  {expandedId === report.id && (
                    <tr key={`detail-${report.id}`} className="border-b border-border/50">
                      <td colSpan={5} className="px-6 py-4 bg-accent/10">
                        <ExpandedView
                          report={report}
                          messages={expandedMessages}
                          messagesLoading={messagesLoading}
                          parseReportData={parseReportData}
                          t={t}
                        />
                      </td>
                    </tr>
                  )}
                </>
              ))}
              {reports.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-muted-foreground">
                    {t("admin.analystReportNoReports")}
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

function ExpandedView({
  report,
  messages,
  messagesLoading,
  parseReportData,
  t,
}: {
  report: AnalystReportSummary;
  messages: AnalystMessageSummary[] | null;
  messagesLoading: boolean;
  parseReportData: (json: string | null) => { analysts: { analystName: string; channelId: string; messageCount: number; contentLength: number }[]; totalMessages: number } | null;
  t: (key: string) => string;
}) {
  const data = parseReportData(report.reportData);

  return (
    <div className="space-y-4">
      {/* Analyst Stats */}
      {data?.analysts && data.analysts.length > 0 && (
        <div>
          <h4 className="text-xs font-medium mb-2 text-muted-foreground uppercase tracking-wider">
            {t("admin.analystReportAnalysts")} ({data.analysts.length})
          </h4>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
            {data.analysts.map((a) => (
              <div key={a.analystName} className="rounded-lg border border-border/50 px-3 py-2 text-xs">
                <div className="font-medium mb-1">{a.analystName}</div>
                <div className="flex gap-3 text-muted-foreground">
                  <span className="flex items-center gap-1">
                    <MessageSquare className="h-3 w-3" />
                    {a.messageCount}
                  </span>
                  <span>{Math.round(a.contentLength / 1000)}K chars</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Messages Collected */}
      {messagesLoading ? (
        <div className="flex items-center gap-2 text-muted-foreground text-sm">
          <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-primary" />
          Loading...
        </div>
      ) : messages && messages.length > 0 ? (
        <div>
          <h4 className="text-xs font-medium mb-2 text-muted-foreground uppercase tracking-wider">
            {t("admin.analystReportMessages")}
          </h4>
          <div className="space-y-2">
            {messages.map((msg) => (
              <div key={msg.analystName} className="rounded-lg border border-border/50 px-3 py-2 text-xs">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-medium">{msg.analystName}</span>
                  <span className="text-muted-foreground">{msg.messageCount} msgs</span>
                </div>
                <div className="text-muted-foreground whitespace-pre-wrap line-clamp-3">
                  {msg.contentPreview}
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div className="text-sm text-muted-foreground">{t("admin.analystReportNoMessages")}</div>
      )}

      {/* AI Report Content */}
      {report.reportContent && (
        <div>
          <h4 className="text-xs font-medium mb-2 text-muted-foreground uppercase tracking-wider">
            {t("admin.analystReportHasContent")}
            {report.aiTokensUsed != null && report.aiTokensUsed > 0 && (
              <span className="ml-2 font-normal">({report.aiTokensUsed} tokens)</span>
            )}
          </h4>
          <div className="rounded-lg border border-border/50 px-4 py-3 text-sm whitespace-pre-wrap leading-relaxed">
            {report.reportContent}
          </div>
        </div>
      )}
    </div>
  );
}
