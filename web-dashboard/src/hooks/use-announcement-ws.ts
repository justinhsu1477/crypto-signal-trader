"use client";

import { useEffect, useRef, useCallback } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";

export interface AnnouncementWsMessage {
  announcementId: number;
  title: string;
  content: string;
  category: string;
  priority: string;
  channels: string;
  publishedAt: string;
  createdBy: string;
}

interface UseAnnouncementWsOptions {
  /** 收到新公告時的 callback */
  onMessage: (msg: AnnouncementWsMessage) => void;
  /** 是否啟用連線（預設 true） */
  enabled?: boolean;
}

/**
 * WebSocket Hook — 訂閱 /topic/announcements
 *
 * 使用 STOMP over SockJS，透過 Next.js rewrite proxy 連線到後端 /ws endpoint。
 * 自動 reconnect（5 秒間隔），unmount 時自動 cleanup。
 */
export function useAnnouncementWs({ onMessage, enabled = true }: UseAnnouncementWsOptions) {
  const clientRef = useRef<Client | null>(null);
  const onMessageRef = useRef(onMessage);

  // 保持 callback 最新（避免 stale closure）
  useEffect(() => {
    onMessageRef.current = onMessage;
  }, [onMessage]);

  const connect = useCallback(() => {
    if (!enabled) return;

    const client = new Client({
      // SockJS factory — 透過 Next.js rewrite /ws → backend:8080/ws
      webSocketFactory: () => new SockJS("/ws") as unknown as WebSocket,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,

      onConnect: () => {
        client.subscribe("/topic/announcements", (message) => {
          try {
            const body: AnnouncementWsMessage = JSON.parse(message.body);
            onMessageRef.current(body);
          } catch {
            // 忽略 parse 錯誤
          }
        });
      },

      onStompError: (frame) => {
        console.error("[AnnouncementWS] STOMP error:", frame.headers["message"]);
      },
    });

    client.activate();
    clientRef.current = client;
  }, [enabled]);

  useEffect(() => {
    connect();

    return () => {
      if (clientRef.current) {
        clientRef.current.deactivate();
        clientRef.current = null;
      }
    };
  }, [connect]);
}
