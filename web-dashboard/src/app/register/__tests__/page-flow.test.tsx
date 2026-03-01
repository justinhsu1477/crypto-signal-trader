import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import RegisterPage from "../page";

const mockRegister = vi.fn();
const mockPush = vi.fn();

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    register: (...args: unknown[]) => mockRegister(...args),
    isAuthenticated: false,
  }),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: (...args: unknown[]) => mockPush(...args),
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
  LegalDisclaimerDialog: ({
    open,
    onAgree,
  }: {
    open: boolean;
    onAgree: () => void;
  }) => (
    open ? <button onClick={onAgree}>agree-legal</button> : null
  ),
}));

describe("RegisterPage flow", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  async function fillBasicFields(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByLabelText("Name"), "Justin");
    await user.type(screen.getByLabelText("Email"), "JUSTIN@Example.com");
    await user.type(screen.getByLabelText("Password"), "Abcdefgh");
  }

  async function agreeTerms(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByText("Terms"));
    await user.click(screen.getByRole("button", { name: "agree-legal" }));
  }

  it("未同意條款前，註冊按鈕保持 disabled", async () => {
    const user = userEvent.setup();
    render(<RegisterPage />);
    await fillBasicFields(user);

    expect(screen.getByRole("button", { name: "Register" })).toBeDisabled();
  });

  it("註冊成功後導向 verify-email，且帶 email query", async () => {
    mockRegister.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<RegisterPage />);

    await fillBasicFields(user);
    await agreeTerms(user);
    await user.click(screen.getByRole("button", { name: "Register" }));

    await waitFor(() => {
      expect(mockRegister).toHaveBeenCalledWith({
        name: "Justin",
        email: "JUSTIN@Example.com",
        password: "Abcdefgh",
        termsAccepted: true,
      });
      expect(mockPush).toHaveBeenCalledWith("/verify-email?email=JUSTIN%40Example.com");
    });
  });

  it("註冊失敗時顯示後端錯誤訊息", async () => {
    mockRegister.mockRejectedValue(new Error("Email already registered"));
    const user = userEvent.setup();
    render(<RegisterPage />);

    await fillBasicFields(user);
    await agreeTerms(user);
    await user.click(screen.getByRole("button", { name: "Register" }));

    await waitFor(() => {
      expect(screen.getByText("Email already registered")).toBeInTheDocument();
    });
  });
});
