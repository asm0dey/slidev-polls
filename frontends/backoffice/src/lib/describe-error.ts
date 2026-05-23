import { AdminApiError } from "./admin-api";

/**
 * Converts an unknown caught value into a human-readable error string.
 *
 * Priority:
 *  1. AdminApiError with field-level validation errors → "field: msg1, msg2; …"
 *  2. AdminApiError with a problem message → that message
 *  3. AdminApiError without a message → "Request failed (HTTP <status>)."
 *  4. Any object with a `.message` string (plain Error, plain {code,message}, …)
 *  5. The fallback parameter
 */
export function describeError(err: unknown, fallback = "Operation failed."): string {
  if (err instanceof AdminApiError) {
    const fieldErrors = err.problem?.errors;
    if (fieldErrors && Object.keys(fieldErrors).length > 0) {
      return Object.entries(fieldErrors)
        .map(([field, msgs]) => `${field}: ${msgs.join(", ")}`)
        .join("; ");
    }
    return err.problem?.message ?? `Request failed (HTTP ${err.status}).`;
  }
  if (err != null && typeof (err as { message?: unknown }).message === "string") {
    return (err as { message: string }).message;
  }
  return fallback;
}
