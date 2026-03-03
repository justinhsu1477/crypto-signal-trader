"use client";

import { useCallback } from "react";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { useT } from "@/lib/i18n/i18n-context";
import { useAnnouncementWs, type AnnouncementWsMessage } from "@/hooks/use-announcement-ws";
import { Bell } from "lucide-react";

/**
 * 全域公告 Listener
 *
 * 放在 AppLayout 內，收到 WebSocket 公告訊息時以 sonner toast 提示用戶。
 * 點擊 toast 可跳轉到 /announcements 頁面。
 */
export function AnnouncementListener() {
  const { t } = useT();
  const router = useRouter();

  const handleMessage = useCallback(
    (msg: AnnouncementWsMessage) => {
      toast(
        <div className="flex items-start gap-3">
          <Bell className="h-5 w-5 text-purple-400 shrink-0 mt-0.5" />
          <div className="min-w-0">
            <div className="font-medium text-sm">{msg.title}</div>
            <div className="text-xs text-muted-foreground mt-0.5 line-clamp-2">
              {msg.content}
            </div>
            <button
              onClick={() => router.push("/announcements")}
              className="text-xs text-purple-400 hover:text-purple-300 mt-1 transition-colors"
            >
              {t("announcement.viewAll")} →
            </button>
          </div>
        </div>,
        {
          duration: 8000,
        }
      );
    },
    [t, router]
  );

  useAnnouncementWs({ onMessage: handleMessage });

  // 不渲染任何 UI
  return null;
}
