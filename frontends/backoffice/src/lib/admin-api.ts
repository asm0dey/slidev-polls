import type {
  AccountView,
  CollaboratorView,
  CreatePollRequest,
  DeckToken,
  LoginRequest,
  Poll,
  PollDetail,
  Problem,
  ProblemCode,
  UpdatePollRequest
} from "@slidev-polls/shared";

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
  onUnauthorized?: () => void;
  /** Returns the cookie string used to extract the CSRF token. Defaults to `document.cookie`. */
  cookieReader?: () => string;
}

/** First-run setup status (GET /api/admin/setup/status). */
export interface SetupStatus {
  setupRequired: boolean;
}

/** First-run setup payload (POST /api/admin/setup). */
export interface SetupRequestBody {
  username: string;
  password: string;
}

/** Create-user payload (POST /api/admin/users). */
export interface CreateUserRequestBody {
  username: string;
  password: string;
}

/** Admin user view returned by setup + user-management endpoints. */
export interface AdminUserView {
  username: string;
  createdAt: string;
  blocked: boolean;
}

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const CSRF_SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);

/**
 * Thin wrapper for the /api/admin/** surface. All calls send credentials so
 * the JSESSIONID cookie set by POST /api/admin/login flows back automatically.
 */
export class AdminApiClient {
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;
  private readonly cookieReader: () => string;
  private onUnauthorized?: () => void;

  constructor(opts: AdminApiOptions = {}) {
    this.baseUrl = (opts.baseUrl ?? "").replace(/\/$/, "");
    this.fetchImpl = opts.fetchImpl ?? fetch.bind(globalThis);
    this.onUnauthorized = opts.onUnauthorized;
    this.cookieReader =
      opts.cookieReader ?? (() => (typeof document !== "undefined" ? document.cookie : ""));
  }

  setOnUnauthorized(handler: (() => void) | undefined): void {
    this.onUnauthorized = handler;
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
    return this.send<PollDetail>("PATCH", `/api/admin/polls/${encodeURIComponent(pollId)}`, body);
  }

  clonePoll(pollId: string): Promise<PollDetail> {
    return this.send<PollDetail>("POST", `/api/admin/polls/${encodeURIComponent(pollId)}/clone`);
  }

  clearVotes(pollId: string): Promise<PollDetail> {
    return this.send<PollDetail>(
      "POST",
      `/api/admin/polls/${encodeURIComponent(pollId)}/votes:clear`
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

  qrUrl(pollId: string): string {
    return `${this.baseUrl}/api/admin/polls/${encodeURIComponent(pollId)}/qr.png`;
  }

  listDeckTokens(pollId: string): Promise<DeckToken[]> {
    return this.send<DeckToken[]>(
      "GET",
      `/api/admin/polls/${encodeURIComponent(pollId)}/deck-tokens`
    );
  }

  revokeDeckToken(pollId: string, tokenId: string): Promise<void> {
    return this.send<void>(
      "DELETE",
      `/api/admin/polls/${encodeURIComponent(pollId)}/deck-tokens/${encodeURIComponent(tokenId)}`,
      undefined,
      false
    );
  }

  getSetupStatus(): Promise<SetupStatus> {
    return this.send<SetupStatus>("GET", "/api/admin/setup/status");
  }

  runSetup(body: SetupRequestBody): Promise<AdminUserView> {
    return this.send<AdminUserView>("POST", "/api/admin/setup", body);
  }

  listUsers(): Promise<AdminUserView[]> {
    return this.send<AdminUserView[]>("GET", "/api/admin/users");
  }

  createUser(body: CreateUserRequestBody): Promise<AdminUserView> {
    return this.send<AdminUserView>("POST", "/api/admin/users", body);
  }

  getAccount(): Promise<AccountView> {
    return this.send<AccountView>("GET", "/api/admin/account");
  }

  changePassword(currentPassword: string, newPassword: string): Promise<void> {
    return this.send<void>(
      "POST",
      "/api/admin/account/password",
      { currentPassword, newPassword },
      false
    );
  }

  resetUserPassword(username: string, newPassword: string): Promise<void> {
    return this.send<void>(
      "POST",
      `/api/admin/users/${encodeURIComponent(username)}/password-reset`,
      { newPassword },
      false
    );
  }

  blockUser(username: string): Promise<void> {
    return this.send<void>(
      "POST",
      `/api/admin/users/${encodeURIComponent(username)}/block`,
      undefined,
      false
    );
  }

  unblockUser(username: string): Promise<void> {
    return this.send<void>(
      "POST",
      `/api/admin/users/${encodeURIComponent(username)}/unblock`,
      undefined,
      false
    );
  }

  listCollaborators(pollId: string): Promise<CollaboratorView[]> {
    return this.send<CollaboratorView[]>(
      "GET",
      `/api/admin/polls/${encodeURIComponent(pollId)}/collaborators`
    );
  }

  addCollaborator(pollId: string, username: string): Promise<CollaboratorView> {
    return this.send<CollaboratorView>(
      "POST",
      `/api/admin/polls/${encodeURIComponent(pollId)}/collaborators`,
      { username }
    );
  }

  removeCollaborator(pollId: string, username: string): Promise<void> {
    return this.send<void>(
      "DELETE",
      `/api/admin/polls/${encodeURIComponent(pollId)}/collaborators/${encodeURIComponent(username)}`,
      undefined,
      false
    );
  }

  transferPoll(pollId: string, newOwnerUsername: string): Promise<PollDetail> {
    return this.send<PollDetail>(
      "POST",
      `/api/admin/polls/${encodeURIComponent(pollId)}/transfer`,
      { newOwnerUsername }
    );
  }

  private async send<T>(
    method: string,
    path: string,
    body?: unknown,
    expectJson = true
  ): Promise<T> {
    const headers: Record<string, string> = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    // CSRF: SecurityConfig protects /api/admin/** (except /login) with CookieCsrfTokenRepository.
    // The backend sets XSRF-TOKEN on every response; we must echo it as X-XSRF-TOKEN on every
    // state-changing call or Spring's CsrfFilter rejects with AccessDeniedException, which the
    // ProblemAccessDeniedHandler maps to HTTP 403 + code FORBIDDEN.
    if (
      !CSRF_SAFE_METHODS.has(method.toUpperCase()) &&
      path !== "/api/admin/login" &&
      path !== "/api/admin/setup"
    ) {
      const token = readCookie(this.cookieReader(), CSRF_COOKIE);
      if (token) headers[CSRF_HEADER] = token;
    }
    const init: RequestInit = {
      method,
      credentials: "same-origin",
      headers: Object.keys(headers).length > 0 ? headers : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined
    };
    const res = await this.fetchImpl(`${this.baseUrl}${path}`, init);
    if (!res.ok) {
      const err = await toAdminError(res);
      if (res.status === 401 && path !== "/api/admin/login" && this.onUnauthorized) {
        this.onUnauthorized();
      }
      throw err;
    }
    if (!expectJson || res.status === 204) {
      return undefined as T;
    }
    return (await res.json()) as T;
  }
}

function readCookie(cookieString: string, name: string): string | null {
  if (!cookieString) return null;
  for (const part of cookieString.split(";")) {
    const eq = part.indexOf("=");
    if (eq === -1) continue;
    const k = part.slice(0, eq).trim();
    if (k === name) {
      return decodeURIComponent(part.slice(eq + 1).trim());
    }
  }
  return null;
}

/**
 * Default singleton client. Pages instantiate one client at module load and
 * share it across navigations; tests inject their own via the apiClient prop.
 */
export const defaultAdminClient = new AdminApiClient();

async function toAdminError(res: Response): Promise<AdminApiError> {
  const contentType = res.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    try {
      const body = (await res.json()) as Partial<Problem>;
      if (body && typeof body.code === "string" && typeof body.message === "string") {
        const problem: Problem = {
          code: body.code as ProblemCode,
          message: body.message,
          correlationId: body.correlationId,
          errors: body.errors
        };
        return new AdminApiError(res.status, problem, problem.message);
      }
    } catch {
      // fall through
    }
  }
  return new AdminApiError(res.status, null, `HTTP ${res.status}`);
}
