"use client";

import { useEffect, useState, useRef } from "react";
import { usePathname } from "next/navigation";
import { getReferralStatus } from "./api";
import type { ReferralStatusEnum } from "@/types";

interface ReferralGuardState {
  isChecking: boolean;
  isVerified: boolean;
  needsReferral: boolean;
}

// Module-level cache: survives re-renders but resets on full page reload.
let cachedStatus: ReferralStatusEnum | null = null;

/** Reset cache — called on logout */
export function clearReferralCache() {
  cachedStatus = null;
}

/**
 * Proactively checks referral status after auth is established.
 * - VERIFIED → cache + allow through
 * - NOT_STARTED / PENDING → needsReferral=true (let UI show dialog)
 * - On /referral page → skip check (prevent infinite loop)
 * - ADMIN role → skip check entirely (backend already bypasses)
 * - API error → fail-open (backend 403 is safety net)
 */
export function useReferralGuard(role?: string | null): ReferralGuardState {
  const pathname = usePathname();
  const isAdmin = role === "ADMIN";
  const isReferralPage = pathname === "/referral";
  const shouldSkipCheck = isAdmin || cachedStatus === "VERIFIED" || isReferralPage;
  const [isChecking, setIsChecking] = useState(() => !shouldSkipCheck);
  const [isVerified, setIsVerified] = useState(() => isAdmin || cachedStatus === "VERIFIED");
  const [needsReferral, setNeedsReferral] = useState(false);
  const hasFetched = useRef(false);

  useEffect(() => {
    // Skip cases: ADMIN, already verified, or on /referral page
    // (useState initializers already set correct initial state for these)
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

        if (result.status === "VERIFIED") {
          setIsVerified(true);
        } else {
          // NOT_STARTED or PENDING → let ReferralGuard show dialog
          setNeedsReferral(true);
        }
      } catch {
        // API failure → fail-open; backend 403 filter is the safety net
      } finally {
        if (!cancelled) setIsChecking(false);
      }
    }

    check();

    return () => { cancelled = true; };
  }, [shouldSkipCheck]);

  return { isChecking, isVerified, needsReferral };
}
