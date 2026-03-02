import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { PublicNavbar } from "../public-navbar";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => {
      const mockTranslations: Record<string, string> = {
        "nav.features": "Features",
        "nav.pricing": "Pricing",
        "landing.featureSecurityTitle": "Security",
        "login.freeRegister": "Sign Up Free",
        "login.signIn": "Sign In",
      };
      return mockTranslations[key] || key;
    },
    locale: "en",
  }),
}));

// Mock usePathname
vi.mock("next/navigation", async () => {
  const actual = await vi.importActual("next/navigation");
  return {
    ...actual,
    usePathname: vi.fn(() => "/login"),
  };
});

// Mock language switcher
vi.mock("@/components/ui/language-switcher", () => ({
  LanguageSwitcher: () => <div data-testid="language-switcher">Language</div>,
}));

// Mock next/link to avoid routing issues in tests
vi.mock("next/link", () => ({
  default: ({
    children,
    href,
  }: {
    children: React.ReactNode;
    href: string;
  }) => <a href={href}>{children}</a>,
}));

// Mock next/image
vi.mock("next/image", () => ({
  default: (props: Record<string, unknown>) => {
    const { alt, src, ...rest } = props;
    return <img alt={alt as string} src={src as string} {...rest} />;
  },
}));

describe("PublicNavbar", () => {
  it("renders without crashing", () => {
    render(<PublicNavbar />);
    expect(screen.getByText("HookFi")).toBeInTheDocument();
  });

  it("renders logo", () => {
    render(<PublicNavbar />);

    const logo = screen.getByAltText("HookFi");
    expect(logo).toBeInTheDocument();
  });

  it("renders navigation links on desktop", () => {
    render(<PublicNavbar />);

    expect(screen.getByText("Features")).toBeInTheDocument();
    expect(screen.getByText("Pricing")).toBeInTheDocument();
    expect(screen.getByText("Security")).toBeInTheDocument();
  });

  it("renders language switcher", () => {
    render(<PublicNavbar />);

    expect(screen.getByTestId("language-switcher")).toBeInTheDocument();
  });

  it("renders Sign In and Sign Up buttons on login page", () => {
    render(<PublicNavbar />);

    expect(screen.getAllByText("Sign In").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Sign Up Free").length).toBeGreaterThan(0);
  });

  it("has fixed positioning for navbar", () => {
    const { container } = render(<PublicNavbar />);

    const nav = container.querySelector("nav");
    expect(nav).toHaveClass("fixed");
    expect(nav).toHaveClass("top-0");
    expect(nav).toHaveClass("left-0");
    expect(nav).toHaveClass("right-0");
    expect(nav).toHaveClass("z-50");
  });

  it("renders with blur backdrop effect", () => {
    const { container } = render(<PublicNavbar />);

    const nav = container.querySelector("nav") as HTMLElement;
    const styles = window.getComputedStyle(nav);

    expect(nav.style.backdropFilter).toContain("blur");
  });

  it("renders navbar at correct height", () => {
    const { container } = render(<PublicNavbar />);

    const div = container.querySelector("div");
    expect(div).toHaveClass("h-16");
  });
});
