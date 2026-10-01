import type { Metadata } from "next";
import type { ReactNode } from "react";
import { AppShell } from "@/components/shell/AppShell";
import { TelemetryBootstrap } from "@/components/TelemetryBootstrap";
import "./globals.css";

export const metadata: Metadata = {
  title: "Platform",
  description: "Multi-tenant application platform",
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <TelemetryBootstrap />
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
