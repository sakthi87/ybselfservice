import { demoEnabled } from "./demo/mode.js";
import { demoResult } from "./demo/respond.js";

export async function gql(path, query, variables) {
  if (demoEnabled()) {
    return { result: demoResult(query, variables) };
  }
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query, variables: variables || {} }),
  });
  const body = await response.json().catch(() => ({}));
  const error = body.errors?.[0];
  if (!response.ok || error) {
    throw new Error(error?.message || body.error || `Request failed (${response.status})`);
  }
  return body.data;
}
