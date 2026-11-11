# AI explanation and the prompt guard (CP-FR-11)

A finding is explained from rule templates today, and from a language model later, through one boundary
that decides what may leave the process. `README.md` lists the decisions to confirm and the gaps.

## Explaining a finding

`ExplanationService` takes an `ExplainRequest` (repository and finding) and tries a chain of `Explainer`s
in order, using the first that produces something:

| Explainer | State | Output |
|---|---|---|
| `ModelExplainer` | Built and tested against a fake model; **not wired** | Model text, `modelGenerated: true`, cacheable |
| `RuleBasedExplainer` | **The only one wired** | Template text, `modelGenerated: false`, not cached |

The service never throws: a failing explainer or cache is skipped. If nothing explains, the answer is empty
(no placeholder). Cacheable output is stored by (fingerprint, rule, generator version), not by repository.

Text that came from a prompt containing source is `sourceInformed`: it is not cached, and a request
waiting on someone else's in-flight call is not given a source-informed answer; it makes its own.
Concurrent requests for the same uncached key share one model call otherwise.

## The prompt guard

`PromptGuard.dispatch(repository, finding, client)` is the only way to reach a `ModelClient`:

1. Read the repository's source-sharing choice. If that fails, treat it as **not opted in**.
2. Only if opted in, ask `SourceSnippetProvider` for source; a failure means no source.
3. Build the prompt from an allow-list: rule id, rule descriptor, category, severity, path, line, message,
   and scalar evidence values (identity-looking keys dropped). Nothing else is read.
4. Record the dispatch (repository, finding ids, fields included, model version, whether source was
   included) in `DispatchLog`. If it cannot be recorded, **nothing is sent**.
5. Call the model.

Repository-controlled text is untrusted: capped, stripped of control characters, fenced between
`<finding>` tags that cannot appear inside it, and the instructions say it is data, not commands.

`GuardedPrompt` has a package-private constructor and `ModelClient` accepts only it, so nothing can reach a
model unguarded. ArchUnit enforces that only the guard calls the model, only the guard makes prompts, and
only `ProvenanceLabeller` makes explanations.

## Provenance

Every `Explanation` carries a `Provenance` (source `MODEL` or `RULE_TEMPLATE`, generator, version,
time). `modelGenerated` is derived from the source. An explanation can only be made through
`ProvenanceLabeller`.

## Keeping a model off the pipeline

A Maven enforcer rule fails the build if any module other than `control-plane-serve` and
`control-plane-app` depends on `control-plane-ai`.

## How the acceptance criteria were read

| AC | Test | Meaning used |
|---|---|---|
| CP-AC-53 | `CpAc53_NoSourceWithoutOptInTest` | Without opt-in no source is fetched or sent, even if a message smuggles a `<source>` tag |
| CP-AC-54 | `CpAc54_SourceIncludedWithOptInLoggedTest` | Opt-in sends source and the dispatch record names repository, finding, fields and model |
| CP-AC-55 | `CpAc55_ExplanationCachedAcrossReposTest` | A second repository with the same finding costs no model call (in-memory cache; Redis later) |
| CP-AC-56 | `CpAc56_GeneratedTextLabelledTest` | Model text `true`, template text `false`, no unlabelled explanation |
| CP-AC-57 | `CpAc57_ModelUnavailableFindingStillShownTest` (serve) | With the model down the finding is still shown, with a labelled template or nothing |
| CP-AC-58 | `CpAc58_RegistryUnavailableRestrictiveModeTest` | An unreadable opt-in means restrictive; the explanation still goes ahead without source |
