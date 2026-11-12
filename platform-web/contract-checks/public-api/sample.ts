// A small program written against the public API, as a customer would write it (Sprint 30, ADR-0248). It is only
// type-checked: it calls every operation of the published contract with the types the document describes, so a contract
// that cannot be used from a generated client fails the build. check.mjs also requires that every operation of the
// document is called here.
import createClient from "openapi-fetch";
import type { components, paths } from "./.generated/schema";

type PublicRecord = components["schemas"]["PublicRecord"];

const client = createClient<paths>({ baseUrl: "https://api.example.test" });

export async function everyOperation(token: string, object: string): Promise<PublicRecord | undefined> {
  const headers = { Authorization: `Bearer ${token}` };

  const description = await client.GET("/api/public/v1/openapi", {});
  const objects = await client.GET("/api/public/v1/objects", { headers });
  const described = await client.GET("/api/public/v1/objects/{object}", { headers, params: { path: { object } } });
  const operators: string[] = described.data?.data.fields.flatMap((field) => field.filterOperators) ?? [];

  const created = await client.POST("/api/public/v1/objects/{object}/records", {
    headers,
    params: { path: { object }, header: { "Idempotency-Key": "order-2026-10-31-0001" } },
    body: { fields: { Name: "A. Example", Salary__c: 1250.5 } },
  });
  const id = created.data?.data.id;
  if (id === undefined) {
    return undefined;
  }

  const read = await client.GET("/api/public/v1/objects/{object}/records/{id}", {
    headers,
    params: { path: { object, id } },
  });
  const etag = read.response.headers.get("ETag") ?? '"0"';

  const changed = await client.PATCH("/api/public/v1/objects/{object}/records/{id}", {
    headers,
    params: { path: { object, id }, header: { "If-Match": etag } },
    body: { fields: { Salary__c: 1300 }, clear: ["Notes__c"] },
  });

  const page = await client.POST("/api/public/v1/objects/{object}/records/query", {
    headers,
    params: { path: { object } },
    body: {
      filter: { op: "AND", items: [{ op: "EQ", field: "Name", values: ["A. Example"] }] },
      sort: [{ field: "Name", descending: true }],
      limit: 50,
    },
  });
  const next = page.data?.pagination.hasMore ? page.data.pagination.nextCursor : undefined;

  const bulk = await client.POST("/api/public/v1/objects/{object}/records/bulk", {
    headers,
    params: { path: { object }, header: { "Idempotency-Key": "batch-2026-10-31-0001" } },
    body: { operation: "CREATE", allOrNone: false, records: [{ fields: { Name: "B" } }] },
  });
  const failed = bulk.data?.data.results.filter((result) => result.status === "FAILED") ?? [];

  await client.DELETE("/api/public/v1/objects/{object}/records/{id}", { headers, params: { path: { object, id } } });
  const restored = await client.POST("/api/public/v1/objects/{object}/records/{id}/restore", {
    headers,
    params: { path: { object, id } },
  });

  void [description, objects, operators, changed, next, failed];
  return restored.data?.data;
}
