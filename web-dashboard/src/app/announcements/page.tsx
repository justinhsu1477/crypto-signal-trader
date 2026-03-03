"use client";

import { useEffect, useState, useCallback } from "react";
import { useT } from "@/lib/i18n/i18n-context";
import { getAnnouncements, markAnnouncementRead, getUnreadAnnouncementCount } from "@/lib/api";
import type { AnnouncementResponse, AnnouncementListResponse } from "@/types";
import { Bell, ChevronLeft, ChevronRight } from "lucide-react";

export default function AnnouncementsPage() {
  const { t } = useT();
  const [data, setData] = useState<AnnouncementListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const pageSize = 10;

  const fetchData = useCallback(() => {
    setLoading(true);
    getAnnouncements(page, pageSize)
      .then(setData)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [page]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  async function handleCardClick(ann: AnnouncementResponse) {
    if (ann.isRead) return;
    try {
      await markAnnouncementRead(ann.id);
      // Update local state
      setData((prev) =>
        prev
          ? {
              ...prev,
              announcements: prev.announcements.map((a) =>
                a.id === ann.id ? { ...a, isRead: true } : a
              ),
              unreadCount: Math.max(0, prev.unreadCount - 1),
            }
          : prev
      );
    } catch {
      // silent fail
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

  function categoryColor(cat: string) {
    const map: Record<string, string> = {
      GENERAL: "bg-blue-500/20 text-blue-400",
      MAINTENANCE: "bg-yellow-500/20 text-yellow-400",
      UPDATE: "bg-green-500/20 text-green-400",
      URGENT: "bg-red-500/20 text-red-400",
      PROMOTION: "bg-purple-500/20 text-purple-400",
    };
    return map[cat] || "bg-gray-500/20 text-gray-400";
  }

  function priorityAccent(pri: string) {
    const map: Record<string, string> = {
      LOW: "border-l-gray-500",
      NORMAL: "border-l-blue-500",
      HIGH: "border-l-orange-500",
      CRITICAL: "border-l-red-500",
    };
    return map[pri] || "border-l-gray-500";
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
        {t("common.loadFailed")}
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-bold">{t("nav.announcements")}</h1>
          {data.unreadCount > 0 && (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-red-500/20 text-red-400">
              {data.unreadCount} {t("announcement.unread")}
            </span>
          )}
        </div>
      </div>

      {/* Announcement Cards */}
      {data.announcements.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
          <Bell className="h-10 w-10 mb-3 opacity-40" />
          <p>{t("announcement.noAnnouncements")}</p>
        </div>
      ) : (
        <div className="space-y-3">
          {data.announcements.map((ann) => (
            <div
              key={ann.id}
              onClick={() => handleCardClick(ann)}
              className={`relative rounded-xl border bg-card p-5 transition-colors cursor-pointer border-l-4 ${priorityAccent(ann.priority)} ${
                ann.isRead
                  ? "border-border opacity-75 hover:opacity-100"
                  : "border-border hover:bg-accent/30"
              }`}
            >
              {/* Unread dot */}
              {!ann.isRead && (
                <div className="absolute top-4 right-4 h-2.5 w-2.5 rounded-full bg-purple-500" />
              )}

              {/* Badges row */}
              <div className="flex items-center gap-2 mb-2">
                <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${categoryColor(ann.category)}`}>
                  {categoryLabel(ann.category)}
                </span>
                {(ann.priority === "HIGH" || ann.priority === "CRITICAL") && (
                  <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
                    ann.priority === "CRITICAL"
                      ? "bg-red-500/20 text-red-400"
                      : "bg-orange-500/20 text-orange-400"
                  }`}>
                    {priorityLabel(ann.priority)}
                  </span>
                )}
                <span className="text-xs text-muted-foreground ml-auto">
                  {ann.publishedAt
                    ? new Date(ann.publishedAt).toLocaleString()
                    : ""}
                </span>
              </div>

              {/* Title */}
              <h3 className={`text-base mb-1 ${ann.isRead ? "font-medium" : "font-bold"}`}>
                {ann.title}
              </h3>

              {/* Content */}
              <p className="text-sm text-muted-foreground leading-relaxed whitespace-pre-wrap">
                {ann.content}
              </p>
            </div>
          ))}
        </div>
      )}

      {/* Pagination */}
      {data.totalPages > 1 && (
        <div className="flex items-center justify-center gap-4 pt-2">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
            className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-sm border border-border hover:bg-accent transition-colors disabled:opacity-40"
          >
            <ChevronLeft className="h-4 w-4" />
            {t("common.previous")}
          </button>
          <span className="text-sm text-muted-foreground">
            {page + 1} / {data.totalPages}
          </span>
          <button
            onClick={() => setPage((p) => Math.min(data.totalPages - 1, p + 1))}
            disabled={page >= data.totalPages - 1}
            className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-sm border border-border hover:bg-accent transition-colors disabled:opacity-40"
          >
            {t("common.next")}
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      )}
    </div>
  );
}
