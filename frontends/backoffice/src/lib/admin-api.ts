import type {
  ActivateQuestionRequest,
  CreatePollRequest,
  LoginRequest,
  Poll,
  PollDetail,
  PollStyle,
  Problem,
  ProblemCode,
  UpdatePollRequest
} from "@polls/shared";

/** Thrown by {@link AdminApiClient} on any non-2xx response. */
export class AdminApiError extends Error {
  readonly status: number;
  readonly problem: Problem | null;

  constructor(status: number, problem: Problem | null, message: string) {
    super(message);
    this.name = "AdminApiError";
    this.status = status;
    this.problem = problem;
  }

  get code(): ProblemCode | undefined {
    return this.problem?.code;
  }
}

export interface AdminApiOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
}

/**
 * Thin wrapper for the /api/admin/** surface. All calls send credentials so
 * the JSESSIONID cookie set by POST /api/admin/login flows back automatically.
 */
export class AdminApiClient {
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;

  constructor(opts: AdminApiOptions = {}) {
    this.baseUrl = (opts.baseUrl ?? "").replace(/\/$/, "");
    this.fetchImpl = opts.fetchImpl ?? fetch.bind(globalThis);
  }

  async login(body: LoginRequest): Promise<void> {
    await this.send("POST", "/api/admin/login", body, /*expectJson*/ false);
  }

  async logout(): Promise<void> {
    await this.send("POST", "/api/admin/logout", undefined, false);
  }

  listPolls(): Promise<Poll[]> {
    return this.send<Poll[]>("GET", "/api/admin/polls");
  }

  getPoll(pollId: string): Promise<PollDetail> {
    return this.send<PollDetail>("GET", `/api/admin/polls/${encodeURIComponent(pollId)}`);
  }

  createPoll(body: CreatePollRequest): Promise<PollDetail> {
    return this.send<PollDetail>("POST", "/api/admin/polls", body);
  }

  updatePoll(pollId: string, body: UpdatePollRequest): Promise<PollDetail> {
    return this.send<PollDetail>(
      "PATCH",
      `/api/admin/polls/${encodeURIComponent(pollId)}`,
      body
    );
  }

  deletePoll(pollId: string): Promise<void> {
    return this.send<void>(
      "DELETE",
      `/api/admin/polls/${encodeURIComponent(pollId)}`,
      undefined,
      false
    );
  }

  activateQuestion(pollId: string, body: ActivateQuestionRequest): Promise<PollDetail> {
    return this.send<PollDetail>(
      "POST",
      `/api/admin/polls/${encodeURIComponent(pollId)}/open`,
      body
    );
  }

  closeActiveQuestion(pollId: string): Promise<PollDetail> {
    return this.send<PollDetail>(
      "POST",
      `/api/admin/polls/${encodeURIComponent(pollId)}/close`
    );
  }

  updateStyle(pollId: string, body: PollStyle): Promise<PollDetail> {
    return this.send<PollDetail>(
      "PUT",
      `/api/admin/polls/${encodeURIComponent(pollId)}/style`,
      body
    );
  }

  qrUrl(pollId: string): string {
    return `${this.baseUrl}/api/admin/polls/${encodeURIComponent(pollId)}/qr.png`;
  }

  private async send<T>(
    method: string,
    path: string,
    body?: unknown,
    expectJson = true
  ): Promise<T> {
    const init: RequestInit = {
      method,
      credentials: "same-origin",
      headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined
    };
    const res = await this.fetchImpl(`${this.baseUrl}${path}`, init);
    if (!res.ok) {
      throw await toAdminError(res);
    }
    if (!expectJson || res.status === 204) {
      return undefined as T;
    }
    return (await res.json()) as T;
  }
}

async function toAdminError(res: Response): Promise<AdminApiError> {
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
        return new AdminApiError(res.status, problem, problem.message);
      }
    } catch {
      // fall through
    }
  }
  return new AdminApiError(res.status, null, `HTTP ${res.status}`);
}
