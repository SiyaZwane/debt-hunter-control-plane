# AI remediation proposals (CP-FR-12)

A finding can have a *proposal* attached to it as a draft pull request: a short document of suggested
next steps, written by rule templates today and by a model later. `README.md` lists the decisions to
confirm and the gaps.

## What a proposal pull request is

A **draft** pull request from a new branch `debt-hunter/fix-<hash>` that adds one file,
`.debt-hunter/proposals/<hash>.md`. The hash comes from the repository and the finding's fingerprint, so
a retry finds its own branch. No source code is changed, the base branch is never written to, and the
pull request is never merged. Its description has the finding id, the rule, where it is, what was found,
the retained evidence, the suggested steps, a statement of who wrote them (templates or a model), and a
link to the run if the control plane has an address configured.

## The path

```
request ──► RemediationRequestService ──► work_queue (kind remediation-pr) ──► RemediationWorker
                │ opt-in, run, finding                                          │ rate limit, opt-in
                │ de-duplicated per repo+finding                                ▼
                                                  RemediationAdvisor ──► FixAgentPrCreator ──► SourceHostClient
```

- **`RemediationRequestService`** accepts a request only if the repository has opted in
  (`RemediationEnabledLookup`; unreadable means no) and the run and finding exist for it. It stores
  identifiers only.
- **`RemediationWorker`** takes requests off the queue. Over the repository's limit: put back until
  later, attempt not spent. Source host down: held. Refused: retried then set aside. Protected branch,
  opted out, or finding gone: set aside at once. Opened or already open: finished.
- **Advisors**: `RuleBasedRemediationAdvisor` (wired) and `ModelRemediationAdvisor` (built, not wired;
  goes through the prompt guard with purpose `REMEDIATE`, so no source is ever sent).
- **`FixAgentPrCreator`**: looks for an open pull request (idempotent), checks the base branch exists,
  asks whether its branch is protected and refuses if so, then makes the branch with the file and opens
  the draft.

## What cannot go wrong, by construction

`SourceHostClient` can look, make a new branch with one file, and open a draft pull request. It has no
merge, approve, update, force-push, delete or protection operation, and only `FixAgentPrCreator` may call
its write methods (ArchUnit). Credentials are only ever put in an `Authorization` header to the
configured host; redirects are not followed; no error message contains one.

## Untrusted text

Everything from the finding (path, message, evidence) is written inside code spans; free text is stripped
of markup with mentions and issue references neutralised; titles are one line of plain text.

## How the acceptance criteria were read

| AC | Test | Meaning used |
|---|---|---|
| CP-AC-59 | `CpAc59_PrOnNonProtectedBranchTest` | A request ends as a draft PR from a new `debt-hunter/fix-*` branch into the base branch, with one proposal file, over PostgreSQL and a GitHub stand-in; the base branch is never written to |
| CP-AC-60 | `CpAc60_BranchProtectionEnforcedTest` | If the host protects the proposal branch (rule or ruleset), nothing is written at all; a protected base branch is not in the way |
| CP-AC-61 | `CpAc61_RateLimitQueuesNotDropsTest` | Over the per-repository limit, requests wait on the queue and are opened as the window allows; none is dropped, none is set aside for waiting |
| CP-AC-62 | `CpAc62_NoSourceInRemediationPromptTest` | A remediation prompt has no source, even for an opted-in repository, and the guard does not ask for it |
