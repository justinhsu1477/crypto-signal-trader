import type { Metadata } from "next";
import { Manrope } from "next/font/google";
import "./globals.css";
import { AppLayout } from "./app-layout";

const manrope = Manrope({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700", "800"],
  display: "swap",
  variable: "--font-manrope",
});

export const metadata: Metadata = {
  title: "HookFi — Smart Crypto Trading",
  description: "HookFi - AI-powered crypto signal trading platform",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={`dark ${manrope.variable}`} suppressHydrationWarning>
      <body className="antialiased">
        <AppLayout>{children}</AppLayout>
      </body>
    </html>
  );
}
