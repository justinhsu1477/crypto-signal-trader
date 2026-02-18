"use client";

import React, { createContext, useContext, useState, useEffect, useCallback } from "react";
import { login as apiLogin, register as apiRegister } from "./api";
import type { LoginRequest, RegisterRequest } from "@/types";

interface AuthState {
  token: string | null;
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
    token: null,
    userId: null,
    email: null,
    isLoading: true,
  });

  // 初始化時從 localStorage 讀取
  useEffect(() => {
    const token = localStorage.getItem("token");
    const userId = localStorage.getItem("userId");
    const email = localStorage.getItem("email");
    setState({ token, userId, email, isLoading: false });
  }, []);

  const login = useCallback(async (data: LoginRequest) => {
    const res = await apiLogin(data);
    localStorage.setItem("token", res.token);
    localStorage.setItem("refreshToken", res.refreshToken);
    localStorage.setItem("userId", res.userId);
    localStorage.setItem("email", res.email);
    setState({ token: res.token, userId: res.userId, email: res.email, isLoading: false });
  }, []);

  const register = useCallback(async (data: RegisterRequest) => {
    await apiRegister(data);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem("token");
    localStorage.removeItem("refreshToken");
    localStorage.removeItem("userId");
    localStorage.removeItem("email");
    setState({ token: null, userId: null, email: null, isLoading: false });
  }, []);

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
