import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";

// Mock dependencies
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    register: vi.fn(),
    isAuthenticated: false,
  }),
}));

vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => {
      const map: Record<string, string> = {
        "register.createAccount": "Create Account",
        "register.subtitle": "Get started",
        "register.name": "Name",
        "register.namePlaceholder": "Your name",
        "login.email": "Email",
        "login.password": "Password",
        "register.passwordRequirements": "Password must contain:",
        "register.passwordMinChars": "At least 8 characters",
        "register.passwordMixedCase": "Both uppercase and lowercase letters",
        "register.agreeTermsPrefix": "I agree to ",
        "register.termsAndConditions": "Terms",
        "register.registerButton": "Register",
        "register.registering": "Registering...",
        "register.registerFailed": "Registration failed",
        "register.hasAccount": "Already have an account? ",
        "register.backToLogin": "Sign in",
      };
      return map[key] || key;
    },
    locale: "en",
  }),
}));

vi.mock("@/components/auth/legal-disclaimer-dialog", () => ({
  LegalDisclaimerDialog: () => null,
}));

import RegisterPage from "../../register/page";

describe("Password Requirements Indicator", () => {
  it("does not show requirements when password is empty", () => {
    render(<RegisterPage />);
    expect(screen.queryByText("Password must contain:")).not.toBeInTheDocument();
  });

  it("shows 2 requirements when password has content", async () => {
    const user = userEvent.setup();
    render(<RegisterPage />);

    const passwordInput = screen.getByPlaceholderText("••••••••");
    await user.type(passwordInput, "a");

    expect(screen.getByText("Password must contain:")).toBeInTheDocument();
    expect(screen.getByText("At least 8 characters")).toBeInTheDocument();
    expect(screen.getByText("Both uppercase and lowercase letters")).toBeInTheDocument();
  });

  it("marks all passing when password meets both requirements", async () => {
    const user = userEvent.setup();
    render(<RegisterPage />);

    const passwordInput = screen.getByPlaceholderText("••••••••");
    await user.type(passwordInput, "Abcdefgh"); // 8 chars + mixed case

    const checks = screen.getAllByText("✓");
    expect(checks.length).toBe(2);
  });

  it("marks failing requirements with circle", async () => {
    const user = userEvent.setup();
    render(<RegisterPage />);

    const passwordInput = screen.getByPlaceholderText("••••••••");
    await user.type(passwordInput, "abc"); // only lowercase, < 8 chars

    // Both checks should fail: < 8 chars, no uppercase
    const failing = screen.getAllByText("○");
    expect(failing.length).toBe(2);
  });

  it("partial pass: 8+ chars but no uppercase", async () => {
    const user = userEvent.setup();
    render(<RegisterPage />);

    const passwordInput = screen.getByPlaceholderText("••••••••");
    await user.type(passwordInput, "abcdefgh"); // 8 chars, all lowercase

    const passing = screen.getAllByText("✓");
    const failing = screen.getAllByText("○");
    expect(passing.length).toBe(1); // min chars
    expect(failing.length).toBe(1); // mixed case
  });
});
