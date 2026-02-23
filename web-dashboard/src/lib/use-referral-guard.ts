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
 * - API error → fail-open (backend 403 is safety net)
 */
export function useReferralGuard(): ReferralGuardState {
  const pathname = usePathname();
  const [isChecking, setIsChecking] = useState(() => cachedStatus !== "VERIFIED");
  const [isVerified, setIsVerified] = useState(() => cachedStatus === "VERIFIED");
  const [needsReferral, setNeedsReferral] = useState(false);
  const hasFetched = useRef(false);

  useEffect(() => {
    // Already verified from cache — skip
    if (cachedStatus === "VERIFIED") {
      setIsChecking(false);
      setIsVerified(true);
      return;
    }

    // On /referral page — don't check (infinite loop prevention)
    if (pathname === "/referral") {
      setIsChecking(false);
      return;
    }

    // Prevent double-fetch in React strict mode
    if (hasFetched.current) return;
    hasFetched.current = true;

    async function check() {
      try {
        const result = await getReferralStatus();
        cachedStatus = result.status;

        if (result.status === "VERIFIED") {
          setIsVerified(true);
          setIsChecking(false);
        } else {
          // NOT_STARTED or PENDING → let ReferralGuard show dialog
          setNeedsReferral(true);
          setIsChecking(false);
        }
      } catch {
        // API failure → fail-open; backend 403 filter is the safety net
        setIsChecking(false);
      }
    }

    check();
  }, [pathname]);

  return { isChecking, isVerified, needsReferral };
}
