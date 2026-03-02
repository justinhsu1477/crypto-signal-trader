"use client";

import { useEffect, useRef } from "react";

/**
 * Adds a CSS class when the element scrolls into viewport.
 * Uses IntersectionObserver for performant scroll-triggered animations.
 */
export function useScrollReveal<T extends HTMLElement = HTMLDivElement>(
  className = "revealed",
  options?: IntersectionObserverInit,
) {
  const ref = useRef<T>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          el.classList.add(className);
          observer.unobserve(el);
        }
      },
      { threshold: 0.15, ...options },
    );

    observer.observe(el);
    return () => observer.disconnect();
  }, [className, options]);

  return ref;
}
