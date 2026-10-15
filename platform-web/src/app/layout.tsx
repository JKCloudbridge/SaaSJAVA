import type { Metadata } from "next";
import localFont from "next/font/local";
import type { ReactNode } from "react";
import { AppShell } from "@/components/shell/AppShell";
import { TelemetryBootstrap } from "@/components/TelemetryBootstrap";
import { SessionProvider } from "@/lib/session/SessionProvider";
import "./globals.css";

// The font files live in this repository (src/fonts) and are served from this application: no request to a third party.
const plexSans = localFont({
  src: [
    { path: "../fonts/ibm-plex-sans-latin-400.woff2", weight: "400", style: "normal" },
    { path: "../fonts/ibm-plex-sans-latin-500.woff2", weight: "500", style: "normal" },
    { path: "../fonts/ibm-plex-sans-latin-600.woff2", weight: "600", style: "normal" },
    { path: "../fonts/ibm-plex-sans-latin-700.woff2", weight: "700", style: "normal" },
  ],
  variable: "--font-plex-sans",
  display: "swap",
});

const plexMono = localFont({
  src: [
    { path: "../fonts/ibm-plex-mono-latin-400.woff2", weight: "400", style: "normal" },
    { path: "../fonts/ibm-plex-mono-latin-500.woff2", weight: "500", style: "normal" },
    { path: "../fonts/ibm-plex-mono-latin-600.woff2", weight: "600", style: "normal" },
  ],
  variable: "--font-plex-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: "Platform",
  description: "Multi-tenant application platform",
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en" className={`${plexSans.variable} ${plexMono.variable}`}>
      <body>
        <TelemetryBootstrap />
        <SessionProvider>
          <AppShell>{children}</AppShell>
        </SessionProvider>
      </body>
    </html>
  );
}
