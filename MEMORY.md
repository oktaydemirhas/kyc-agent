# MEMORY

## Decisions
- Spring Boot 4.1 + plain `RestClient` instead of Spring AI: fewer moving parts, same OpenAI-compatible client idea as `rag-eval`. Boot 4 uses Jackson 3 (`tools.jackson.*`).
- LLM extracts, code decides. The model is never asked for a verdict.
- PII vault: model gets placeholders and returns placeholders; the server resolves them (Argon's suggestion from the rag-eval review).

## Lessons
- Default locale on this machine is Turkish: `"expiryDate".toUpperCase()` returned `EXPİRYDATE`. Always pass `Locale.ROOT` for identifiers and codes. A unit test caught it.
- Never trust a value the model returns without checking it against the source text or the vault.

- Unit tests passed while a rule was unreachable (`TCKN_INVALID` never fired because bad-checksum numbers were not masked). Only the end-to-end eval with a real LLM exposed it: test the pipeline, not just the pieces.
- Holdout (written first, run once after dev fixes) scored 18/20; the 2 misses are denylist gaps. Not patched on purpose, to keep the holdout honest. The holdout test reports but does not fail the build; the dev test enforces zero unsafe approvals.

- Closed the denylist gap structurally: `instructionsFound` from the model, OR-ed with the denylist. Measured on a NEW holdout (`holdout2.json`, 8 unseen injections + 8 harmless look-alikes): 16/16 over 3 runs. Holdout 1 is now a seen set.

## Next
- Persist decisions (see below); a larger, adversarial injection set (multi-turn, encoded, non-Turkish/English) before claiming more.
- Persist decisions (Postgres) with masked evidence only; add a reviewer queue.

- 2026-11-10 review: dates the model returned were never checked against the text, so a manipulated model could get an expired ID of a minor APPROVEd. Grounding has to cover every field, not only identifiers; every returned date must now appear in the document. Forwarding headers are client-controlled: never key a rate limiter on them by default.
