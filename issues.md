# Forward git_handoff: recipient owns structure

## Problem

Reverse (`non-forwarding`) `git_handoff` already tells the recipient that
the inbound tree is the structure. Forward `to:` hops do not. Structure
improves as work moves down the pack (architect is a large hop; hardender
and QA still improve shape). An upstream sender's layout must not dominate
a downstream worktree. A downstream handback's layout must.

## Behavior

Constitution (handoffs article): structure improves as work moves down the
pack. On a reverse (`non-forwarding`) `git_handoff`, the inbound tree is
the structure. Replay this role's current task onto that shape. Do not keep
the pre-inbound layout in order to save local work. On a forward
`git_handoff`, this role's current tree is the structure. Replay the inbound
work onto that shape. Do not adopt the inbound layout in order to save
merge work. Do not use "refactored".

`non-forwarding` is the handback discriminator: reverse copies and the last
window's send. Those recipients are earlier, so inbound wins. A forward
`to:` hop is the next window, so local wins.

Draft extra lines after the headers are ignored, as they are today.
`swarm_handoff` always writes the delivered body. `handoffd` copies those
files as-is and does not strip the body.

The forward file's body stays Re-read plus `merge_and_process.sh <sender>
<commit>`, then: this role's current tree is the structure; replay the
inbound work onto that shape. Each reverse / last-window `non-forwarding`
file's body stays Re-read plus `merge_and_process`, then: the inbound tree
is the structure; replay this role's current task onto that shape.

## Verification

Cover a forward `to:` hop whose delivered body contains `merge_and_process`
and a current-tree structure instruction, and a reverse `non-forwarding`
copy whose body contains `merge_and_process` and an inbound-tree structure
instruction. Cover last-window `git_handoff` using the inbound-tree
instruction, not the current-tree one. Cover extra lines in the agent draft
not appearing in either delivered body. Do not pin prompt wording.

# Codex card status is the last work bullet

## Problem

Codex reports work as `•` prose bullets (`The htw card is committed…`,
`The specification now makes every random domain explicit…`). Card status
still looks for I'll / I'm / let me / continue, so a work bullet with none
of those is ignored and a later I'll clause or helper line can win.

## Behavior

For Codex roles only, card status is the last `•` sentence that is not
tool chrome. Throwaway starters: Working, Ran, Edited, Added, Searching,
Searched. Also throw away the usage-limit bullet (`You have 1 usage limit
reset available`). Other backends keep the current I'll / I'm / let me /
continue path.

Non-bullet throwaways stay as they are: collapsed transcript, helper
audit copy, mail banner. An unmatched poll keeps the last good status.

## Verification

Cover a Codex pane whose last work bullet has no I'll / I'm, after Ran /
Edited / Working / Searching / Added / Searched and a usage-limit bullet:
the card status is that work bullet, not the chrome. Cover a non-Codex
role still using I'll status. Do not pin prompt wording.

# Retry documents show last diff and comment history

## Problem

On retry, Documents still opens the current file as a blank slate. The
operator does not see what changed since the rejected offer, and the
remedial comments they typed on that file are gone. Comments live on the
approval id and are dropped at retry. `/doc` only slurps the worktree
file. Pane inject is not a document view.

## Behavior

Keep comments by task, not by approval id. On retry, persist each path's
comment with the rejected snapshot (`rejected/<task-id>/latest` already
exists). When the next pending handoff for that task is held, Documents
opens with that history. Drop the store on Approve or Delete, not on
Retry.

The document window has three panes. Only the last is editable:

1. Document — current file, or a colored diff. A checkbox chooses whole
   file vs diff. Diff is only this offer vs the last rejected version.
   The full document is shown: unchanged lines black, added/removed
   lines in color. With no prior reject, only the whole file; the diff
   choice is off.
2. History — earlier comments for that path, oldest first, each round
   under a timestamped separator. Its own scroll. Read-only.
3. New comment — this review only. Save or Retry appends a timestamped
   block to history.

Retry-dialog comments stay on the task inject. They do not appear in a
file's history unless typed in that document's new-comment box.

## Verification

Cover a first Attention document: whole file, empty history, no diff
choice. Cover Save then Retry then a new pending offer: history shows
the saved comment under a timestamp; new comment is empty; diff vs
`rejected/<task-id>/latest` colors changed lines and leaves unchanged
lines black; whole-file checkbox shows the current file. Cover Approve
or Delete clearing the stored history. Do not pin prompt wording.

# Show a transient merging card for handbacks

## Problem

A reverse (`non-forwarding`) `git_handoff` does not move the board card.
The merging role's swimlane still shows whatever card sits in that lane.
Live four-pack: coder was merging refactorer→coder `htw` while `jump`
sat in coder. The jump card said waiting in queue. The swimlane did not
show that coder was merging htw.

## Behavior

While a role has a reverse (`non-forwarding`) `git_handoff` in process,
the dashboard shows a transient merging card in that role's lane for
the inbound task. Its background is light yellow. It carries pane
status like any other in-process card. It is not a pipeline card: it
does not move the real task, does not go to Done, and does not go
through Attention.

When that mail is completed, the merging card disappears. Other cards
in that lane stay waiting in queue until the role is free for a
forward hop.

## Verification

Cover four-pack refactorer `back-one` to coder while coder `new` also
has a `50` next-card git_handoff: with the `00` htw reverse copy in
process, the swimlane shows a light-yellow merging card for htw with
pane status, and the next card still says waiting in queue. After
`done_with_current` on the reverse copy, the merging card is gone and
the real htw card is still in its forward lane. Do not pin prompt
wording.
