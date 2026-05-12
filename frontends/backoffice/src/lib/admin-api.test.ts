import { describe, expect, it, vi } from "vitest";
import { AdminApiClient, AdminApiError } from "./admin-api";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" }
  });
}

function emptyResponse(status: number): Response {
  return new Response(null, { status });
}

describe("AdminApiClient CSRF handling (BUG-004 regression)", () => {
  it("attaches X-XSRF-TOKEN on POST when XSRF-TOKEN cookie is present", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(emptyResponse(204));
    const client = new AdminApiClient({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      cookieReader: () => "JSESSIONID=abc; XSRF-TOKEN=tok-123"
    });

    await client.createPoll({
      title: "x",
      questions: [{ prompt: "p", options: [{ label: "a" }, { label: "b" }] }]
    });

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const init = fetchImpl.mock.calls[0][1] as RequestInit;
    const headers = init.headers as Record<string, string>;
    expect(headers["X-XSRF-TOKEN"]).toBe("tok-123");
    expect(headers["Content-Type"]).toBe("application/json");
  });

  it("URL-decodes the XSRF-TOKEN cookie value before sending it", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(emptyResponse(204));
    const client = new AdminApiClient({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      cookieReader: () => "XSRF-TOKEN=abc%3D%3D"
    });

    await client.deletePoll("p1");

    const headers = (fetchImpl.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    expect(headers["X-XSRF-TOKEN"]).toBe("abc==");
  });

  it("does not attach X-XSRF-TOKEN on safe GET requests", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(200, []));
    const client = new AdminApiClient({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      cookieReader: () => "XSRF-TOKEN=tok-123"
    });

    await client.listPolls();

    const init = fetchImpl.mock.calls[0][1] as RequestInit;
    expect(init.headers).toBeUndefined();
  });

  it("does not attach X-XSRF-TOKEN on POST /api/admin/login (CSRF-exempt)", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(emptyResponse(204));
    const client = new AdminApiClient({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      cookieReader: () => "XSRF-TOKEN=tok-123"
    });

    await client.login({ username: "alice", password: "pw" });

    const headers = (fetchImpl.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    expect(headers["X-XSRF-TOKEN"]).toBeUndefined();
    expect(headers["Content-Type"]).toBe("application/json");
  });

  it("omits X-XSRF-TOKEN if no XSRF-TOKEN cookie is present", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(emptyResponse(204));
    const client = new AdminApiClient({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      cookieReader: () => ""
    });

    await client.deletePoll("p1");

    const init = fetchImpl.mock.calls[0][1] as RequestInit;
    const headers = (init.headers ?? {}) as Record<string, string>;
    expect(headers["X-XSRF-TOKEN"]).toBeUndefined();
  });

  it("propagates 403 FORBIDDEN as AdminApiError unchanged when CSRF token is missing", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse(403, { code: "FORBIDDEN", message: "access denied" }));
    const client = new AdminApiClient({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      cookieReader: () => ""
    });

    await expect(
      client.createPoll({
        title: "x",
        questions: [{ prompt: "p", options: [{ label: "a" }, { label: "b" }] }]
      })
    ).rejects.toMatchObject({
      name: "AdminApiError",
      status: 403,
      code: "FORBIDDEN"
    });
  });

  it("does not call onUnauthorized for non-401 responses", async () => {
    const onUnauthorized = vi.fn();
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse(403, { code: "FORBIDDEN", message: "access denied" }));
    const client = new AdminApiClient({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      cookieReader: () => "XSRF-TOKEN=t",
      onUnauthorized
    });

    await expect(client.deletePoll("p1")).rejects.toBeInstanceOf(AdminApiError);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});

describe("AdminApiClient setup + user management", () => {
  it("getSetupStatus calls GET /api/admin/setup/status", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(200, { setupRequired: true }));
    const client = new AdminApiClient({ fetchImpl: fetchImpl as unknown as typeof fetch });
    const status = await client.getSetupStatus();
    expect(status).toEqual({ setupRequired: true });
    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/admin/setup/status",
      expect.objectContaining({ method: "GET" })
    );
  });

  it("runSetup posts to /api/admin/setup without CSRF token", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(201, {
        username: "alice",
        createdAt: "2026-05-09T00:00:00Z"
      })
    );
    const client = new AdminApiClient({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      cookieReader: () => "XSRF-TOKEN=tok-123"
    });
    const result = await client.runSetup({
      username: "alice",
      password: "correct-horse-battery"
    });
    expect(result.username).toBe("alice");
    const init = fetchImpl.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe("POST");
    // Setup happens before any session exists; CSRF cookie must NOT be echoed.
    const headers = (init.headers ?? {}) as Record<string, string>;
    expect(headers["X-XSRF-TOKEN"]).toBeUndefined();
  });

  it("listUsers calls GET /api/admin/users", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(200, []));
    const client = new AdminApiClient({ fetchImpl: fetchImpl as unknown as typeof fetch });
    await client.listUsers();
    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/admin/users",
      expect.objectContaining({ method: "GET" })
    );
  });

  it("createUser posts to /api/admin/users with CSRF token", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(201, {
        username: "bob",
        createdAt: "2026-05-09T00:00:00Z"
      })
    );
    const client = new AdminApiClient({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      cookieReader: () => "XSRF-TOKEN=tok-456"
    });
    const result = await client.createUser({
      username: "bob",
      password: "another-strong-pw"
    });
    expect(result.username).toBe("bob");
    const init = fetchImpl.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe("POST");
    const headers = init.headers as Record<string, string>;
    // /api/admin/users is authenticated → CSRF token MUST be echoed.
    expect(headers["X-XSRF-TOKEN"]).toBe("tok-456");
  });

  it("createUser surfaces USERNAME_TAKEN as a typed AdminApiError", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(409, {
        code: "USERNAME_TAKEN",
        message: "username already taken: alice",
        correlationId: "corr-1"
      })
    );
    const client = new AdminApiClient({
      fetchImpl: fetchImpl as unknown as typeof fetch,
      cookieReader: () => "XSRF-TOKEN=t"
    });
    await expect(
      client.createUser({ username: "alice", password: "another-strong-pw" })
    ).rejects.toMatchObject({
      name: "AdminApiError",
      status: 409,
      problem: { code: "USERNAME_TAKEN" }
    });
  });
});
