"use client";

import Image from "next/image";

/**
 * Large logo with gentle floating animation + subtle glow pulse.
 * Uses CSS keyframes instead of anime.js (npm blocked).
 */
export function HeroOrbitVisual() {
  return (
    <div
      aria-hidden="true"
      className="relative flex items-center justify-center"
    >
      {/* Soft glow behind logo */}
      <div className="hero-glow-light absolute inset-[-15%] rounded-full" />

      {/* Animated logo */}
      <Image
        src="/logo-transparent.png"
        alt="HookFi logo"
        width={640}
        height={592}
        priority
        className="hero-float-light relative h-auto w-full max-w-[220px] md:max-w-[380px] object-contain"
      />
    </div>
  );
}
