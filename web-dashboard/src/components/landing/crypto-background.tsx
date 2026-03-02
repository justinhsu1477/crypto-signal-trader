"use client";

/**
 * Light warm background with stronger radial gradients for better section visibility.
 * Matching lido.fi style but with more visible color washes.
 */
export function CryptoBackground() {
  return (
    <div className="fixed inset-0 pointer-events-none" style={{ zIndex: 0 }}>
      {/* Warm off-white base */}
      <div
        className="absolute inset-0"
        style={{ background: "rgb(255, 248, 247)" }}
      />
      {/* Pink radial — top left (smoother, wider fade) */}
      <div
        className="absolute -left-[10%] -top-[10%] h-[90vh] w-[90vh] rounded-full"
        style={{
          background: "radial-gradient(ellipse at center, rgba(255,180,195,0.35), rgba(255,180,195,0.1) 50%, transparent 80%)",
        }}
      />
      {/* Lavender radial — bottom right (smoother, wider fade) */}
      <div
        className="absolute -bottom-[10%] -right-[10%] h-[85vh] w-[85vh] rounded-full"
        style={{
          background: "radial-gradient(ellipse at center, rgba(200,180,255,0.3), rgba(200,180,255,0.08) 50%, transparent 80%)",
        }}
      />
      {/* Subtle warm wash — center (wider blend) */}
      <div
        className="absolute top-[35%] left-[25%] h-[70vh] w-[70vh] rounded-full"
        style={{
          background: "radial-gradient(ellipse at center, rgba(255,220,200,0.18), transparent 75%)",
        }}
      />
    </div>
  );
}
