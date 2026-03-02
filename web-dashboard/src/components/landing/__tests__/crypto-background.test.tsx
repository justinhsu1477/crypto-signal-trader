import { render } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { CryptoBackground } from "../crypto-background";

describe("CryptoBackground", () => {
  it("renders without crashing", () => {
    const { container } = render(<CryptoBackground />);
    expect(container.firstChild).toBeInTheDocument();
  });

  it("renders fixed positioned container", () => {
    const { container } = render(<CryptoBackground />);

    const mainDiv = container.querySelector('[class*="fixed"]');
    expect(mainDiv).toBeInTheDocument();
    expect(mainDiv?.className).toContain("inset-0");
    expect(mainDiv?.className).toContain("pointer-events-none");
  });

  it("renders with correct z-index", () => {
    const { container } = render(<CryptoBackground />);

    const mainDiv = container.firstChild as HTMLElement;
    expect(mainDiv.style.zIndex).toBe("0");
  });

  it("renders base background div with correct color", () => {
    const { container } = render(<CryptoBackground />);

    const baseBg = container.querySelector("div:nth-child(1)");
    const bgDiv = baseBg?.querySelector("div");
    expect(bgDiv?.style.background).toBe("rgb(255, 248, 247)");
  });

  it("renders pink radial gradient for top-left", () => {
    const { container } = render(<CryptoBackground />);

    const pinkGradient = container.querySelectorAll("div[class*='rounded-full']")[0] as HTMLElement;
    expect(pinkGradient).toBeInTheDocument();
    expect(pinkGradient.style.background).toContain("radial-gradient");
    expect(pinkGradient.style.background).toContain("rgba(255, 180, 195");
  });

  it("renders lavender radial gradient for bottom-right", () => {
    const { container } = render(<CryptoBackground />);

    const lavenderGradient = container.querySelectorAll(
      "div[class*='rounded-full']"
    )[1] as HTMLElement;
    expect(lavenderGradient).toBeInTheDocument();
    expect(lavenderGradient.style.background).toContain("radial-gradient");
    expect(lavenderGradient.style.background).toContain("rgba(200, 180, 255");
  });

  it("renders warm wash gradient in center", () => {
    const { container } = render(<CryptoBackground />);

    const warmGradient = container.querySelectorAll(
      "div[class*='rounded-full']"
    )[2] as HTMLElement;
    expect(warmGradient).toBeInTheDocument();
    expect(warmGradient.style.background).toContain("radial-gradient");
    expect(warmGradient.style.background).toContain("rgba(255, 220, 200");
  });

  it("renders 3 gradient divs", () => {
    const { container } = render(<CryptoBackground />);

    const gradients = container.querySelectorAll("div[class*='rounded-full']");
    expect(gradients.length).toBeGreaterThanOrEqual(3);
  });

  it("renders gradients as absolutely positioned", () => {
    const { container } = render(<CryptoBackground />);

    const gradients = container.querySelectorAll("div[class*='absolute']");
    expect(gradients.length).toBeGreaterThan(0);
  });

  it("gradients have correct sizing", () => {
    const { container } = render(<CryptoBackground />);

    const gradients = container.querySelectorAll("div[class*='rounded-full']");
    expect(gradients[0]?.className).toContain("h-");
    expect(gradients[0]?.className).toContain("w-");
  });
});
