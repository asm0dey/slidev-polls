import { describe, it, expect, vi } from "vitest";
import { ApiClient } from "./api-client";

describe("ApiClient.retractVote", () => {
  it("issues DELETE and resolves on 204", async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 }));
    const client = new ApiClient({ fetchImpl });

    await expect(client.retractVote("my-talk")).resolves.toBeUndefined();
    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/polls/my-talk/votes",
      expect.objectContaining({ method: "DELETE", credentials: "same-origin" })
    );
  });

  it("throws ApiError on non-2xx", async () => {
    const fetchImpl = vi.fn(
      async () =>
        new Response(JSON.stringify({ code: "QUESTION_NOT_ACTIVE", message: "closed" }), {
          status: 409,
          headers: { "content-type": "application/json" }
        })
    );
    const client = new ApiClient({ fetchImpl });

    await expect(client.retractVote("my-talk")).rejects.toMatchObject({
      name: "ApiError",
      status: 409,
      problem: expect.objectContaining({ code: "QUESTION_NOT_ACTIVE" })
    });
  });
});
