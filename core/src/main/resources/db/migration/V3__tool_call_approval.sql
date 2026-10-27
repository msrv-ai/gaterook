-- M1.5 (review round 1): a call that repeated a pending call reused its approval request, and gets the request's
-- resolved and completed rows too (ARCHITECTURE.md §4 "Аудит", §5 step 11). The writer finds those calls by the
-- request in the transaction of the decision, so the lookup must not scan the whole audit.
CREATE INDEX tool_call_approval ON tool_call (approval_id) WHERE approval_id IS NOT NULL;
