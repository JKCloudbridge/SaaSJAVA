import type { NextConfig } from "next";

/**
 * The browser only ever talks to its own origin. Requests under /api go to the platform API (the API decides
 * tenant, identity and permissions; nothing here does), and, when a trace collector is configured, /telemetry carries
 * the browser's spans to it. Same origin means no CORS rules to maintain and cookies that stay first-party.
 * In a deployment the ingress does the same routing and these rewrites are simply not reached.
 */
const apiOrigin = process.env.PLATFORM_API_ORIGIN ?? "http://localhost:8080";
const traceCollectorOrigin = process.env.PLATFORM_TRACE_COLLECTOR_ORIGIN;

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // Assistant instruction files are not generated into the repository (project rule: they are never committed).
  agentRules: false,
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${apiOrigin}/api/:path*` },
      ...(traceCollectorOrigin
        ? [{ source: "/telemetry/:path*", destination: `${traceCollectorOrigin}/:path*` }]
        : []),
    ];
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
        ],
      },
    ];
  },
};

export default nextConfig;
