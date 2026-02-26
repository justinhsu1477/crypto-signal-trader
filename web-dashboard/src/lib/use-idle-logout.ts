"use client";

import { useEffect, useRef, useCallback } from "react";

const IDLE_TIMEOUT_MS = 30 * 60 * 1000; // 30 分鐘
const EVENTS: (keyof DocumentEventMap)[] = [
  "mousemove",
  "mousedown",
  "keydown",
  "touchstart",
  "scroll",
];

/**
 * 閒置自動登出 hook
 *
 * 偵測 30 分鐘無滑鼠/鍵盤/觸控操作，自動呼叫 logout（清除 HttpOnly Cookie）並踢回登入頁。
 * 僅在使用者已登入時啟用。
 */
export function useIdleLogout(isAuthenticated: boolean, logout: () => void) {
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const resetTimer = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      logout();
      if (typeof window !== "undefined") {
        window.location.href = "/login";
      }
    }, IDLE_TIMEOUT_MS);
  }, [logout]);

  useEffect(() => {
    // 未登入不啟動
    if (!isAuthenticated) return;

    resetTimer();

    for (const event of EVENTS) {
      document.addEventListener(event, resetTimer, { passive: true });
    }

    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
      for (const event of EVENTS) {
        document.removeEventListener(event, resetTimer);
      }
    };
  }, [isAuthenticated, resetTimer]);
}
