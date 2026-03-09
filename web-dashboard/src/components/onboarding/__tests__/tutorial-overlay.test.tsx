import { render, screen, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { TutorialOverlay } from "../tutorial-overlay";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string, params?: Record<string, unknown>) => {
      const map: Record<string, string> = {
        "tutorial.welcomeTitle": "Welcome to HookFi",
        "tutorial.welcomeDesc": "Let us show you around.",
        "tutorial.kpiTitle": "KPI Cards",
        "tutorial.kpiDesc": "See your key performance indicators.",
        "tutorial.riskTitle": "Risk Budget",
        "tutorial.riskDesc": "Monitor your risk budget.",
        "tutorial.positionsTitle": "Positions",
        "tutorial.positionsDesc": "View your open positions.",
        "tutorial.sidebarTitle": "Sidebar",
        "tutorial.sidebarDesc": "Navigate between sections.",
        "tutorial.skip": "Skip",
        "tutorial.prev": "Previous",
        "tutorial.nextStep": "Next",
        "tutorial.finish": "Finish",
      };
      if (key === "tutorial.stepOf" && params) {
        return `${params.current} / ${params.total}`;
      }
      return map[key] || key;
    },
    locale: "en",
  }),
}));

beforeEach(() => {
  // Reset localStorage (handled by setup.tsx mock)
  window.localStorage.clear();
  vi.clearAllMocks();
});

describe("TutorialOverlay", () => {
  it("renders when tutorial not completed (no localStorage)", () => {
    render(<TutorialOverlay />);
    expect(screen.getByText("Welcome to HookFi")).toBeInTheDocument();
    expect(screen.getByText("Let us show you around.")).toBeInTheDocument();
  });

  it("does not render when tutorial completed (localStorage set)", () => {
    window.localStorage.setItem("tutorial-completed", "true");
    render(<TutorialOverlay />);
    expect(screen.queryByText("Welcome to HookFi")).not.toBeInTheDocument();
  });

  it("shows step counter 1 / 5 on first step", () => {
    render(<TutorialOverlay />);
    expect(screen.getByText("1 / 5")).toBeInTheDocument();
  });

  it("does not show Previous button on first step", () => {
    render(<TutorialOverlay />);
    expect(screen.queryByText("Previous")).not.toBeInTheDocument();
  });

  it("clicking Next advances to step 2", async () => {
    const user = userEvent.setup();
    render(<TutorialOverlay />);

    await user.click(screen.getByText("Next"));

    expect(screen.getByText("KPI Cards")).toBeInTheDocument();
    expect(screen.getByText("2 / 5")).toBeInTheDocument();
  });

  it("shows Previous button on step 2+", async () => {
    const user = userEvent.setup();
    render(<TutorialOverlay />);

    await user.click(screen.getByText("Next"));

    expect(screen.getByText("Previous")).toBeInTheDocument();
  });

  it("clicking Previous goes back", async () => {
    const user = userEvent.setup();
    render(<TutorialOverlay />);

    // Go to step 2
    await user.click(screen.getByText("Next"));
    expect(screen.getByText("KPI Cards")).toBeInTheDocument();

    // Go back to step 1
    await user.click(screen.getByText("Previous"));
    expect(screen.getByText("Welcome to HookFi")).toBeInTheDocument();
    expect(screen.getByText("1 / 5")).toBeInTheDocument();
  });

  it("last step shows Finish button", async () => {
    const user = userEvent.setup();
    render(<TutorialOverlay />);

    // Navigate to last step (5 steps total)
    for (let i = 0; i < 4; i++) {
      await user.click(screen.getByText("Next"));
    }

    expect(screen.getByText("Sidebar")).toBeInTheDocument();
    expect(screen.getByText("5 / 5")).toBeInTheDocument();
    expect(screen.getByText("Finish")).toBeInTheDocument();
    expect(screen.queryByText("Next")).not.toBeInTheDocument();
  });

  it("clicking Finish sets localStorage and hides overlay", async () => {
    const user = userEvent.setup();
    render(<TutorialOverlay />);

    // Navigate to last step
    for (let i = 0; i < 4; i++) {
      await user.click(screen.getByText("Next"));
    }

    await user.click(screen.getByText("Finish"));

    expect(window.localStorage.setItem).toHaveBeenCalledWith("tutorial-completed", "true");
    expect(screen.queryByText("Sidebar")).not.toBeInTheDocument();
  });

  it("clicking Skip sets localStorage and hides overlay", async () => {
    const user = userEvent.setup();
    render(<TutorialOverlay />);

    await user.click(screen.getByText("Skip"));

    expect(window.localStorage.setItem).toHaveBeenCalledWith("tutorial-completed", "true");
    expect(screen.queryByText("Welcome to HookFi")).not.toBeInTheDocument();
  });

  it("navigates through all steps correctly", async () => {
    const user = userEvent.setup();
    render(<TutorialOverlay />);

    const expectedSteps = [
      { title: "Welcome to HookFi", step: "1 / 5" },
      { title: "KPI Cards", step: "2 / 5" },
      { title: "Risk Budget", step: "3 / 5" },
      { title: "Positions", step: "4 / 5" },
      { title: "Sidebar", step: "5 / 5" },
    ];

    // Check first step
    expect(screen.getByText(expectedSteps[0].title)).toBeInTheDocument();
    expect(screen.getByText(expectedSteps[0].step)).toBeInTheDocument();

    // Navigate through remaining steps
    for (let i = 1; i < expectedSteps.length; i++) {
      await user.click(screen.getByText(i < 4 ? "Next" : "Next"));
      expect(screen.getByText(expectedSteps[i].title)).toBeInTheDocument();
      expect(screen.getByText(expectedSteps[i].step)).toBeInTheDocument();
    }
  });

  it("ESC key closes the tutorial", async () => {
    render(<TutorialOverlay />);
    expect(screen.getByText("Welcome to HookFi")).toBeInTheDocument();

    // Fire Escape keydown event
    act(() => {
      window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    });

    expect(window.localStorage.setItem).toHaveBeenCalledWith("tutorial-completed", "true");
    expect(screen.queryByText("Welcome to HookFi")).not.toBeInTheDocument();
  });
});
