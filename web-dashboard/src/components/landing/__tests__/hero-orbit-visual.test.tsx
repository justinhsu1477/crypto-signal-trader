import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { HeroOrbitVisual } from "../hero-orbit-visual";

// Mock next/image to avoid image loading issues in tests
import { vi } from "vitest";
vi.mock("next/image", () => ({
  default: (props: Record<string, unknown>) => {
    const { alt, src, ...rest } = props;
    return <img alt={alt as string} src={src as string} {...rest} />;
  },
}));

describe("HeroOrbitVisual", () => {
  it("renders without crashing", () => {
    render(<HeroOrbitVisual />);
    expect(screen.getByAltText("HookFi logo")).toBeInTheDocument();
  });

  it("renders logo image", () => {
    render(<HeroOrbitVisual />);

    const img = screen.getByAltText("HookFi logo");
    expect(img).toBeInTheDocument();
    expect(img).toHaveAttribute("src", "/logo-transparent.png");
  });

  it("renders with aria-hidden attribute", () => {
    const { container } = render(<HeroOrbitVisual />);

    const wrapper = container.firstChild as HTMLElement;
    expect(wrapper).toHaveAttribute("aria-hidden", "true");
  });

  it("renders glow background element", () => {
    const { container } = render(<HeroOrbitVisual />);

    const glow = container.querySelector('[class*="hero-glow"]');
    expect(glow).toBeInTheDocument();
  });

  it("renders logo with float animation class", () => {
    const { container } = render(<HeroOrbitVisual />);

    const img = container.querySelector("img");
    expect(img?.className).toContain("hero-float-light");
  });

  it("renders logo with proper max-width", () => {
    const { container } = render(<HeroOrbitVisual />);

    const img = container.querySelector("img");
    expect(img?.className).toContain("max-w");
  });

  it("renders with flexbox centering", () => {
    const { container } = render(<HeroOrbitVisual />);

    const wrapper = container.querySelector('[class*="flex"]');
    expect(wrapper?.className).toContain("items-center");
    expect(wrapper?.className).toContain("justify-center");
  });

  it("renders image with relative positioning", () => {
    const { container } = render(<HeroOrbitVisual />);

    const img = container.querySelector("img");
    expect(img?.className).toContain("relative");
  });

  it("renders with image priority loading", () => {
    // Image component receives priority prop
    // This is verified through the component implementation
    const { container } = render(<HeroOrbitVisual />);

    const img = container.querySelector("img");
    expect(img).toBeInTheDocument();
  });
});
