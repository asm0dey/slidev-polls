import type { PublicPollView } from "./types";

export interface ApiClientOptions {
  baseUrl?: string;
}

export class ApiClient {
  private readonly baseUrl: string;

  constructor(opts: ApiClientOptions = {}) {
    this.baseUrl = (opts.baseUrl ?? "").replace(/\/$/, "");
  }

  async publicPoll(slug: string): Promise<PublicPollView> {
    const res = await fetch(`${this.baseUrl}/api/polls/by-slug/${encodeURIComponent(slug)}`, {
      credentials: "same-origin"
    });
    if (!res.ok) {
      throw new Error(`publicPoll ${slug} failed: ${res.status}`);
    }
    return (await res.json()) as PublicPollView;
  }

  async submitVote(slug: string, optionId: string, voterToken: string): Promise<void> {
    const res = await fetch(`${this.baseUrl}/api/polls/${encodeURIComponent(slug)}/votes`, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ optionId, voterToken })
    });
    if (!res.ok) {
      throw await buildProblem(res);
    }
  }
}

async function buildProblem(res: Response): Promise<Error> {
  try {
    const body = await res.json();
    const err = new Error(body?.message ?? `HTTP ${res.status}`);
    (err as Error & { code?: string }).code = body?.code;
    return err;
  } catch {
    return new Error(`HTTP ${res.status}`);
  }
}
