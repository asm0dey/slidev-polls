export type PollStatus = "DRAFT" | "OPEN" | "CLOSED";
export type QuestionStatus = "DRAFT" | "ACTIVE" | "CLOSED";

export interface PollStyle {
  primaryColor?: string;
  accentColor?: string;
  backgroundColor?: string;
  fontFamily?: string;
  layout?: "BARS" | "COLUMNS" | "DONUT";
}

export interface PollOption {
  id: string;
  label: string;
  position: number;
}

export interface Question {
  id: string;
  prompt: string;
  ordinal: number;
  status: QuestionStatus;
  options: PollOption[];
}

export interface PublicPollView {
  pollId: string;
  slug: string;
  title: string;
  state: "WAITING" | "ACTIVE";
  style: PollStyle;
  activeQuestion?: Question;
  alreadyVoted?: boolean;
}

export interface TallyEntry {
  optionId: string;
  count: number;
}

export interface SnapshotEvent {
  pollId: string;
  slug: string;
  activeQuestion: Question | null;
  tally: TallyEntry[];
  emittedAt: string;
}

export interface TallyDeltaEvent {
  pollId: string;
  questionId: string;
  optionId: string;
  count: number;
  emittedAt: string;
}
