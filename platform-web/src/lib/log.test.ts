import { afterEach, describe, expect, it } from "vitest";
import { log, setLogSink } from "./log";

describe("log", () => {
  let restore: (() => void) | undefined;
  afterEach(() => restore?.());

  it("writes one JSON object per call in the backend's vocabulary", () => {
    const lines: string[] = [];
    restore = setLogSink((line) => lines.push(line));

    log("warn", "something happened", { requestId: "req_1", traceId: "t_1", tenantId: "tenant-a" });

    expect(lines).toHaveLength(1);
    expect(JSON.parse(lines[0]!)).toMatchObject({
      "log.level": "warn",
      "log.logger": "platform-web",
      message: "something happened",
      requestId: "req_1",
      traceId: "t_1",
      tenantId: "tenant-a",
    });
    expect(JSON.parse(lines[0]!)["@timestamp"]).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  it("leaves out the tenant when none is known", () => {
    const lines: string[] = [];
    restore = setLogSink((line) => lines.push(line));

    log("info", "no tenant yet", { requestId: "req_2" });

    expect(JSON.parse(lines[0]!)).not.toHaveProperty("tenantId");
  });
});
