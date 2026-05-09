// Mirrors the schemas in specs/001-polling-foundation/contracts/openapi.yaml.
// Keep this file and the OpenAPI document in lock-step so the voter, backoffice,
// and slidev-addon packages all speak the same DTOs.

export type PollStatus = "DRAFT" | "OPEN" | "CLOSED";
export type QuestionStatus = "DRAFT" | "ACTIVE" | "CLOSED";

export interface PollStyle {
  primaryColor?: string;
  accentColor?: string;
  backgroundColor?: string;
  fontFamily?: string;
  layout?: "BARS" | "COLUMNS" | "DONUT";
}

export interface Option {
  id: string;
  label: string;
  position: number;
}

export interface Question {
  id: string;
  prompt: string;
  ordinal: number;
  status: QuestionStatus;
  options: Option[];
}

/** Back-compat alias for callers that imported `PollOption`; prefer `Option`. */
export type PollOption = Option;

/** Backoffice-side poll summary (GET /api/admin/polls). */
export interface Poll {
  id: string;
  title: string;
  slug: string;
  status: PollStatus;
  publicUrl: string;
  activeQuestionId: string | null;
}

/** Full poll with questions, options, and style (GET /api/admin/polls/{id}). */
export interface PollDetail extends Poll {
  style: PollStyle;
  questions: Question[];
  allowedOrigins?: string[];
}

/** Public-facing poll view (GET /api/polls/by-slug/{slug}). */
export interface PublicPollView {
  pollId: string;
  slug: string;
  title: string;
  state: "WAITING" | "ACTIVE";
  style: PollStyle;
  activeQuestion?: Question;
  alreadyVoted?: boolean;
}

/** Login payload (POST /api/admin/login). */
export interface LoginRequest {
  username: string;
  password: string;
}

/** Option payload inside CreateQuestionRequest. */
export interface CreateOptionRequest {
  label: string;
}

/** Question payload for create/update (POST /api/admin/polls). */
export interface CreateQuestionRequest {
  prompt: string;
  options: CreateOptionRequest[];
}

/** Create-poll payload (POST /api/admin/polls). */
export interface CreatePollRequest {
  title: string;
  slug?: string;
  style?: PollStyle;
  questions: CreateQuestionRequest[];
  allowedOrigins?: string[];
}

/** Patch-style update payload (PATCH /api/admin/polls/{id}). */
export interface UpdatePollRequest {
  title?: string;
  slug?: string;
  questions?: CreateQuestionRequest[];
  allowedOrigins?: string[];
}

/** Activate-question payload (POST /api/admin/polls/{id}/open). */
export interface ActivateQuestionRequest {
  questionId: string;
}

/** Vote submission payload (POST /api/polls/{slug}/votes). */
export interface VoteRequest {
  optionId: string;
  voterToken: string;
}

/** Vote accepted response. */
export interface VoteAccepted {
  voteId: string;
  /** ISO-8601 timestamp at which the vote was persisted. */
  recordedAt: string;
}

/** OpenAPI Problem error envelope. */
export type ProblemCode =
  | "AUTH_REQUIRED"
  | "FORBIDDEN"
  | "NOT_FOUND"
  | "VALIDATION_FAILED"
  | "ALREADY_VOTED"
  | "QUESTION_NOT_ACTIVE"
  | "ACTIVATION_REJECTED"
  | "SLUG_TAKEN"
  | "SLUG_INVALID"
  | "SLUG_RESERVED"
  | "DECK_TOKEN_INVALID"
  | "DECK_TOKEN_POLL_MISMATCH"
  | "ORIGIN_INVALID"
  | "SETUP_LOCKED"
  | "USERNAME_TAKEN"
  | "TRANSPORT_FAILURE";

export interface Problem {
  code: ProblemCode;
  message: string;
  correlationId?: string;
}

/** Deck token as listed in the backoffice (GET /api/admin/polls/{id}/deck-tokens). */
export interface DeckToken {
  id: string;
  pollId: string;
  label: string | null;
  createdAt: string;
  revokedAt: string | null;
}

/** Freshly minted deck token — plaintext bearer returned once (POST .../deck-tokens). */
export interface DeckTokenMinted extends DeckToken {
  plaintext: string;
}

export interface MintDeckTokenRequest {
  label?: string | null;
}

/** Slimmed snapshot of an active question — what the SSE `snapshot` event carries.
 *  Lacks `status` because the SSE stream is only emitted for the currently-active question. */
export interface SnapshotActiveQuestion {
  id: string;
  prompt: string;
  ordinal: number;
  options: Option[];
}

/** SSE "snapshot" event — full state for a poll/question, re-emitted on (re)connect. */
export interface SnapshotEvent {
  pollId: string;
  slug: string;
  activeQuestion: SnapshotActiveQuestion | null;
  tally: TallyEntry[];
  emittedAt: string;
}

export interface TallyEntry {
  optionId: string;
  count: number;
}

/** SSE "tally" event — delta for a single option, fan-out on each accepted vote. */
export interface TallyDeltaEvent {
  pollId: string;
  questionId: string;
  optionId: string;
  count: number;
  emittedAt: string;
}

/** SSE "question-closed" event. */
export interface QuestionClosedEvent {
  pollId: string;
  questionId: string;
  emittedAt: string;
}
