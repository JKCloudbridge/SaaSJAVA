import type { Metadata } from "next";
import type { ReactNode } from "react";
import { AppShell } from "@/components/shell/AppShell";
import { TelemetryBootstrap } from "@/components/TelemetryBootstrap";
import { SessionProvider } from "@/lib/session/SessionProvider";
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
        <SessionProvider>
          <AppShell>{children}</AppShell>
        </SessionProvider>
      </body>
    </html>
  );
}
