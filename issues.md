# Label Accept on the reject dialog as Accept Unchanged

## Problem

The reject dialog's Accept button is labeled `Accept`. That does not say
the action ignores retry comments and approves the pending handoff as
it stands.

## Behavior

The button still calls the existing Accept path. Its visible label is
`Accept Unchanged`.

## Verification

Cover the reject dialog showing `Accept Unchanged` on `#rt-accept`.
Do not pin prompt wording.

# Keep card status off helper and transcript chrome

## Problem

Card status is the last pane sentence that looks like agent work. Collapsed
Codex transcript lines (`… +15 lines (ctrl + t to view transcript)`) and
handoff-helper copy (`this audit against the revised candidate before
running the handoff command again`) still qualify. `other-status?` treats
any sentence with `run` as status. Live `ts` showed that helper text
instead of what specifier was doing.

## Behavior

Status ignores collapsed-transcript chrome and helper audit/queue
instructions. It still keeps I'll / I'm / let me / continue work
sentences. An unmatched poll keeps the last good status.

## Verification

Cover a pane tail that ends with a collapsed transcript line and
`running the handoff command again`: the card status is not that text
when an I'll sentence is earlier in the tail. Do not pin prompt wording.

# Keep pack_web.pid while the dashboard is serving

## Problem

`--serve` is supposed to write `.swarmforge/pack_web.pid` so teardown can
`kill` that process. On the live four-squad the dashboard was listening
and `handoffd` was up, but that pid file was missing. Teardown that
looks up the pid then misses the server.

## Behavior

While `pack_web --serve` is running, `.swarmforge/pack_web.pid` exists
and holds that process id. Teardown still reads it, signals the process
if it is not itself, and deletes the file.

## Verification

Cover `--serve` creating `.swarmforge/pack_web.pid` with the server pid,
and teardown removing the file. Do not pin prompt wording.

# Configure git_handoff reverse propagation per role

## Problem

Each role only merges inbound from the previous role. When card A is
still downstream, card B is built on the sender's own last commit, so
structural work (especially the architect's) is overwritten and redone.
The only reverse sync today is hardcoded: the last pack role is always
`non-forwarding` and constitution tells that agent to list every other
role. That Done broadcast cannot be chosen, narrowed, or applied earlier
in the chain.

## Behavior

Window lines take an optional propagation token after receive-mode
(`task`/`batch`), before extra CLI args: `forward-only`, `back-one`,
`back-all`. Omitted is `forward-only`. Store it on the roles.tsv row.
Blank receive-mode stays `task`.

The agent still drafts `to: <next>` (the following window). Last window
has no next; it still queues a `git_handoff`, but `to:` does not move
the card. `swarm_handoff` delivers merge-only copies separately from
`to:`. Do not add reverse roles onto `to:` — a `to:` of every other
role is today's terminal broadcast and would Done the card.

Propagation only names those merge-only copies:

- `forward-only`: none
- `back-one`: the previous window
- `back-all`: every earlier window (upstream only, never the next
  role or anyone after it)

Those copies are `non-forwarding`. They do not move the board card and
do not go through Attention. They queue at priority `00` so they sort
ahead of ordinary `50` mail. When the sender is not last, `to:` moves
the card to the next window. The forward handoff keeps the draft
priority.

`back-all` is not Done. The card goes Done only when the last window
in the pipeline queues a `git_handoff`. Keep that last-agent rule.
Do not use last-window to decide reverse recipients, and do not use
`back-all` to decide Done.

Four-pack: specifier `forward-only` (default), coder `forward-only`,
refactorer `back-one`, architect `back-all`. Receive-mode is unchanged
(architect `batch`). Architect is last, so its `git_handoff` marks
Done; `back-all` merges that commit into specifier, coder, and
refactorer (every earlier window).

Two-pack: coder `forward-only` (default), cleaner `back-one` or
`back-all`. Only coder is earlier, so those reverse sets are the same
recipient. Cleaner is last, so its `git_handoff` marks Done either way.

Six-pack: specifier `forward-only` (default), coder `forward-only`,
cleaner `back-one`, architect `back-all`, hardender `forward-only`,
QA `back-all`. Architect is not last: merge-only copies go to
specifier, coder, and cleaner; `to:` is hardender; the card moves to
hardender and is not Done; QA does not get a reverse copy. QA is last:
its `git_handoff` marks Done; `back-all` merges that commit into every
earlier window.

Idle `ready_for_next` merges reverse mail and completes; busy roles
leave it in `new`. `merge_and_process` still skips an ancestor SHA.

Constitution (handoffs article): on a reverse (`non-forwarding`)
`git_handoff`, merge the inbound commit. The inbound tree is the
structure. Replay this role's current task onto that shape. Do not keep
the pre-inbound layout in order to save local work. Do not use
"refactored"; reverse copies are not only from the refactorer/cleaner
role. This rule does not apply to the forward `to:` hop.

Draft extra lines after the headers are ignored, as they are today.
`swarm_handoff` always writes the delivered body. Reverse copies are
separate outbox files from the forward hop: one file has one body, so
a shared `to:` list cannot carry a reverse-only instruction.

The forward file's body stays the current helper text (`Re-read your
role and constitution.` plus `merge_and_process.sh <sender> <commit>`).
Each reverse file's body is that same pair plus an instruction that the
inbound tree is the structure and current work is replayed onto it.
`handoffd` copies those files as-is and does not strip the body.

## Verification

Cover conf parse: omitted token is `forward-only`; `back-one` /
`back-all` round-trip in roles.tsv; extra CLI args after the token
still apply. Cover four-pack refactorer `back-one` to architect: coder
gets a `non-forwarding` copy that is not on `to:`; its filename
priority is `00`; lane is architect; Attention is not held for coder;
card is not Done. When coder `new` also has a `50` next-card note, the
`00` reverse copy is first. Cover four-pack
architect `back-all`: specifier, coder, and refactorer get merge-only;
card goes Done because architect is last. Cover six-pack architect
`back-all`: specifier, coder, and cleaner get merge-only; QA does not;
lane is hardender; card is not Done. Cover six-pack QA `back-all`:
every earlier window gets merge-only; card goes Done because QA is last. Cover two-pack cleaner `back-one`: coder gets
merge-only; card goes Done because cleaner is last. Cover last window
`forward-only`: card still goes Done; no reverse copies. Cover a reverse copy as its own outbox/inbox file, not a second name
on the forward `to:` list. Cover that file's body containing
`merge_and_process` and a structure-replay instruction, and the
matching forward file's body containing `merge_and_process` without
that instruction. Cover extra lines in the agent draft not appearing
in either delivered body. Do not pin prompt wording.
