# Slidev Polls Premise

## What

Slidev Polls is an audience polling system integrated into [Slidev](https://sli.dev) developer presentations. It lets presenters embed interactive polls (multiple choice, rating, free-text) directly in their markdown slides and collect live responses from audience members on their own devices, with results rendered in real time on the presentation itself.

## Who

Developers, engineers, and technical speakers who use Slidev to deliver talks at conferences, meetups, internal team sessions, workshops, and training — and the audiences attending those sessions who respond to polls from a phone or laptop without installing software.

## Why

Slidev is the go-to markdown-based slide tool for developer talks, but it lacks a built-in way to gather live audience feedback. Existing polling services (Slido, Mentimeter, Kahoot) are paid, require tab-switching out of the deck, and break the developer-friendly, markdown-first authoring flow. Slidev Polls keeps polls as first-class markdown components alongside code blocks and diagrams, so the presenter never leaves their deck and audience engagement becomes as easy to version-control as the rest of the talk.

## Domain

Live presentation tooling and real-time audience engagement. Key terms: **Slidev** (markdown-based slide framework), **poll** (a question with one or more response options), **presenter** (authors and runs the deck), **respondent** (audience member submitting an answer), **session** (an instance of a running presentation with a join code), **response aggregate** (the tallied live results rendered back onto the slide).

## Scope

**In scope**: Slidev component/addon for authoring polls in markdown; a lightweight backend for session creation, response collection, and real-time aggregation; a respondent-facing web page (mobile-friendly, no install); result visualizations embeddable in slides; session lifecycle (open/close/reset) controlled by the presenter.

**Out of scope**: native mobile apps, persistent long-term analytics dashboards, user accounts/auth for respondents, monetization/billing, quiz scoring/leaderboards (may be a future extension), integrations with non-Slidev presentation tools.
