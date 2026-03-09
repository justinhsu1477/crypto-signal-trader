import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { AiConfidenceBadge } from "../ai-confidence-badge";

describe("AiConfidenceBadge", () => {
  it("null confidence → does not render", () => {
    const { container } = render(
      <AiConfidenceBadge confidence={null} reasoning={null} />,
    );
    expect(container.innerHTML).toBe("");
  });

  it("undefined confidence → does not render", () => {
    const { container } = render(
      <AiConfidenceBadge confidence={undefined} reasoning={undefined} />,
    );
    expect(container.innerHTML).toBe("");
  });

  it("confidence >= 80 → green badge", () => {
    render(<AiConfidenceBadge confidence={90} reasoning="Strong trend" />);
    const badge = screen.getByText("AI 90");
    expect(badge).toBeInTheDocument();
    expect(badge.className).toContain("emerald");
  });

  it("confidence = 80 → green badge (boundary)", () => {
    render(<AiConfidenceBadge confidence={80} reasoning={null} />);
    const badge = screen.getByText("AI 80");
    expect(badge.className).toContain("emerald");
  });

  it("confidence 60-79 → yellow badge", () => {
    render(<AiConfidenceBadge confidence={65} reasoning="Moderate signal" />);
    const badge = screen.getByText("AI 65");
    expect(badge).toBeInTheDocument();
    expect(badge.className).toContain("yellow");
  });

  it("confidence = 60 → yellow badge (boundary)", () => {
    render(<AiConfidenceBadge confidence={60} reasoning={null} />);
    const badge = screen.getByText("AI 60");
    expect(badge.className).toContain("yellow");
  });

  it("confidence 40-59 → orange badge", () => {
    render(<AiConfidenceBadge confidence={50} reasoning="Weak signal" />);
    const badge = screen.getByText("AI 50");
    expect(badge).toBeInTheDocument();
    expect(badge.className).toContain("orange");
  });

  it("confidence = 40 → orange badge (boundary)", () => {
    render(<AiConfidenceBadge confidence={40} reasoning={null} />);
    const badge = screen.getByText("AI 40");
    expect(badge.className).toContain("orange");
  });

  it("confidence < 40 → red badge", () => {
    render(<AiConfidenceBadge confidence={20} reasoning="Very weak" />);
    const badge = screen.getByText("AI 20");
    expect(badge).toBeInTheDocument();
    expect(badge.className).toContain("red");
  });

  it("confidence = 0 → red badge", () => {
    render(<AiConfidenceBadge confidence={0} reasoning={null} />);
    const badge = screen.getByText("AI 0");
    expect(badge.className).toContain("red");
  });

  it("reasoning displayed as title attribute", () => {
    render(<AiConfidenceBadge confidence={85} reasoning="Strong breakout pattern" />);
    const badge = screen.getByText("AI 85");
    expect(badge).toHaveAttribute("title", "Strong breakout pattern");
  });

  it("null reasoning → no title attribute", () => {
    render(<AiConfidenceBadge confidence={85} reasoning={null} />);
    const badge = screen.getByText("AI 85");
    expect(badge).not.toHaveAttribute("title");
  });
});
