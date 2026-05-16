import type { Problem, ProblemCode, PublicPollView, VoteAccepted, VoteRequest } from "./types";

export interface ApiClientOptions {
  baseUrl?: string;
  /** Optional injection point for tests. */
  fetchImpl?: typeof fetch;
}

/**
 * Thrown by {@link ApiClient} on any non-2xx response. Carries the parsed
 * {@link Problem} envelope so callers can branch on a typed {@code code}
 * rather than scraping HTTP status codes.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | null;

  constructor(status: number, problem: Problem | null, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }

  get code(): ProblemCode | undefined {
    return this.problem?.code;
  }

  get correlationId(): string | undefined {
    return this.problem?.correlationId ?? undefined;
  }
}

export class ApiClient {
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;

  constructor(opts: ApiClientOptions = {}) {
    this.baseUrl = (opts.baseUrl ?? "").replace(/\/$/, "");
    // Browsers require window.fetch to be called with `this === window`. When we
    // store the global `fetch` on an instance and later invoke it as
    // `this.fetchImpl(…)`, the receiver becomes the ApiClient and Chrome rejects
    // the call with "Failed to execute 'fetch' on 'Window': Illegal invocation"
    // (surfaced by BUG-005). Binding to globalThis keeps the call site simple
    // while preserving the test injection hook.
    this.fetchImpl = opts.fetchImpl ?? fetch.bind(globalThis);
  }

  async publicPoll(slug: string): Promise<PublicPollView> {
    const res = await this.fetchImpl(
      `${this.baseUrl}/api/polls/by-slug/${encodeURIComponent(slug)}`,
      { credentials: "same-origin" }
    );
    if (!res.ok) {
      throw await toApiError(res);
    }
    return (await res.json()) as PublicPollView;
  }

  async submitVote(slug: string, body: VoteRequest): Promise<VoteAccepted | null> {
    const res = await this.fetchImpl(
      `${this.baseUrl}/api/polls/${encodeURIComponent(slug)}/votes`,
      {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
      }
    );
    if (!res.ok) {
      throw await toApiError(res);
    }
    // The 201 body is VoteAccepted; 204 is tolerated for back-compat.
    if (res.status === 204) {
      return null;
    }
    return (await res.json()) as VoteAccepted;
  }

  async retractVote(slug: string): Promise<void> {
    const res = await this.fetchImpl(
      `${this.baseUrl}/api/polls/${encodeURIComponent(slug)}/votes`,
      {
        method: "DELETE",
        credentials: "same-origin"
      }
    );
    if (!res.ok) {
      throw await toApiError(res);
    }
  }
}

/**
 * Parses a Problem envelope from a non-2xx response. The server always returns
 * application/json for API failures, but fall back to a plain message if the
 * body cannot be decoded (e.g., a gateway injected an HTML error page) so the
 * caller still gets a meaningful error.
 */
async function toApiError(res: Response): Promise<ApiError> {
  const contentType = res.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    try {
      const body = (await res.json()) as Partial<Problem>;
      if (body && typeof body.code === "string" && typeof body.message === "string") {
        const problem: Problem = {
          code: body.code as ProblemCode,
          message: body.message,
          correlationId: body.correlationId
        };
        return new ApiError(res.status, problem, problem.message);
      }
    } catch {
      // fall through
    }
  }
  return new ApiError(res.status, null, `HTTP ${res.status}`);
}
