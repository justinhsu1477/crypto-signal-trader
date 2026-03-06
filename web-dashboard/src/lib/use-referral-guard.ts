"use client";

import { useEffect, useState, useRef, useCallback } from "react";
import { usePathname } from "next/navigation";
import { getReferralStatus } from "./api";
import type { ReferralStatusEnum, ReferralStatusResponse } from "@/types";

interface ReferralGuardState {
  isChecking: boolean;
  isVerified: boolean;
  needsReferral: boolean;
  referralStatus: ReferralStatusEnum | null;
  referralData: ReferralStatusResponse | null;
  /** 重新檢查 referral 狀態（例如 Dialog 提交 UID 後） */
  refresh: () => void;
}

// Module-level cache: survives re-renders but resets on full page reload.
let cachedStatus: ReferralStatusEnum | null = null;
let cachedData: ReferralStatusResponse | null = null;

/** Reset cache — called on logout */
export function clearReferralCache() {
  cachedStatus = null;
  cachedData = null;
  if (typeof window !== "undefined") {
    sessionStorage.removeItem("referral-banner-dismissed");
  }
}

/**
 * Proactively checks referral status after auth is established.
 * - VERIFIED → cache + hide banner
 * - NOT_STARTED / PENDING → needsReferral=true (let UI show banner/dialog)
 * - On /referral page → skip check (prevent infinite loop)
 * - ADMIN role → skip check entirely
 * - API error → fail-open (不擋用戶)
 */
export function useReferralGuard(role?: string | null): ReferralGuardState {
  const pathname = usePathname();
  const isAdmin = role === "ADMIN";
  const isReferralPage = pathname === "/referral";
  const shouldSkipCheck = isAdmin || cachedStatus === "VERIFIED" || isReferralPage;
  const [isChecking, setIsChecking] = useState(() => !shouldSkipCheck);
  const [isVerified, setIsVerified] = useState(() => isAdmin || cachedStatus === "VERIFIED");
  const [needsReferral, setNeedsReferral] = useState(false);
  const [referralStatus, setReferralStatus] = useState<ReferralStatusEnum | null>(cachedStatus);
  const [referralData, setReferralData] = useState<ReferralStatusResponse | null>(cachedData);
  const hasFetched = useRef(false);

  const fetchStatus = useCallback(async () => {
    try {
      setIsChecking(true);
      const result = await getReferralStatus();
      cachedStatus = result.status;
      cachedData = result;
      setReferralStatus(result.status);
      setReferralData(result);

      if (result.status === "VERIFIED") {
        setIsVerified(true);
        setNeedsReferral(false);
      } else {
        // NOT_STARTED or PENDING → let ReferralGuard show dialog/banner
        setNeedsReferral(true);
      }
    } catch {
      // API failure → fail-open（不擋用戶進 Dashboard）
    } finally {
      setIsChecking(false);
    }
  }, []);

  useEffect(() => {
    // Skip cases: ADMIN, already verified, or on /referral page
    if (shouldSkipCheck) return;

    // Prevent double-fetch in React strict mode
    if (hasFetched.current) return;
    hasFetched.current = true;

    let cancelled = false;

    async function check() {
      try {
        const result = await getReferralStatus();
        if (cancelled) return;
        cachedStatus = result.status;
        cachedData = result;
        setReferralStatus(result.status);
        setReferralData(result);

        if (result.status === "VERIFIED") {
          setIsVerified(true);
        } else {
          setNeedsReferral(true);
        }
      } catch {
        // API failure → fail-open
      } finally {
        if (!cancelled) setIsChecking(false);
      }
    }

    check();

    return () => { cancelled = true; };
  }, [shouldSkipCheck]);

  const refresh = useCallback(() => {
    hasFetched.current = false;
    fetchStatus();
  }, [fetchStatus]);

  return { isChecking, isVerified, needsReferral, referralStatus, referralData, refresh };
}
