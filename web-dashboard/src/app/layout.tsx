import type { Metadata } from "next";
import "./globals.css";
import { AppLayout } from "./app-layout";

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
    <html lang="en" className="dark" suppressHydrationWarning>
      <head>
        <link
          href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&display=swap"
          rel="stylesheet"
        />
      </head>
      <body className="antialiased">
        <AppLayout>{children}</AppLayout>
      </body>
    </html>
  );
}
