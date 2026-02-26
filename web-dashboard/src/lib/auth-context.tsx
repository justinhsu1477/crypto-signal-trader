"use client";

import React, { createContext, useContext, useState, useEffect, useCallback } from "react";
import { login as apiLogin, register as apiRegister, fetchCurrentUser, apiLogout } from "./api";
import { clearReferralCache } from "./use-referral-guard";
import { useIdleLogout } from "./use-idle-logout";
import type { LoginRequest, RegisterRequest } from "@/types";

interface AuthState {
  isAuthenticated: boolean;
  userId: string | null;
  email: string | null;
  isLoading: boolean;
}

interface AuthContextType extends AuthState {
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>({
    isAuthenticated: false,
    userId: null,
    email: null,
    isLoading: true,
  });

  // 初始化：呼叫 /api/auth/me 確認登入狀態（HttpOnly Cookie 自動帶上）
  useEffect(() => {
    const initAuth = async () => {
      try {
        const user = await fetchCurrentUser();
        // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR hydration: must check auth state after mount
        setState({
          isAuthenticated: true,
          userId: user.userId,
          email: user.email,
          isLoading: false,
        });
      } catch {
        // 未登入或 cookie 過期
        // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR hydration
        setState({
          isAuthenticated: false,
          userId: null,
          email: null,
          isLoading: false,
        });
      }
    };
    initAuth();
  }, []);

  const login = useCallback(async (data: LoginRequest) => {
    const res = await apiLogin(data);
    // Token 已透過 Set-Cookie 設定，只需更新 UI 狀態
    setState({
      isAuthenticated: true,
      userId: res.userId,
      email: res.email,
      isLoading: false,
    });
  }, []);

  const register = useCallback(async (data: RegisterRequest) => {
    await apiRegister(data);
  }, []);

  const logout = useCallback(async () => {
    // 呼叫後端清除 HttpOnly Cookie
    await apiLogout();
    clearReferralCache();
    setState({
      isAuthenticated: false,
      userId: null,
      email: null,
      isLoading: false,
    });
  }, []);

  // 30 分鐘無操作自動登出
  useIdleLogout(state.isAuthenticated, logout);

  return (
    <AuthContext.Provider value={{ ...state, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
