export type OriginValidation =
  | { ok: true; normalized: string }
  | { ok: false; message: string };

export function validateOrigin(input: string): OriginValidation {
  const v = input.trim();
  if (v.length === 0) return { ok: false, message: "Origin is empty" };
  if (v === "*") return { ok: true, normalized: "*" };
  let url: URL;
  try {
    url = new URL(v);
  } catch {
    // If the input already has an http/https scheme but still fails to parse,
    // it's missing a valid host rather than a scheme.
    if (/^https?:\/\//i.test(v)) {
      return { ok: false, message: "Origin needs a host" };
    }
    return {
      ok: false,
      message: "Origin needs a scheme (e.g. https://example.com)"
    };
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    return { ok: false, message: "Origin scheme must be http or https" };
  }
  if (!url.host) {
    return { ok: false, message: "Origin needs a host" };
  }
  return { ok: true, normalized: `${url.protocol}//${url.host}` };
}
