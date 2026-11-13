# Data governance

## What is personal data here

Who touched a finding. A run bundle carries real contributor identities (names, emails). The rule is
absolute: **contributor identity never reaches the system of record readable.** It is replaced by a stable
keyed pseudonym first ([how](pseudonymisation.md)), and there is exactly one permissioned, audited path back.

Nothing derived from the system of record carries an identity either: not the read APIs, not the analytics
read models, not a digest or a work item. That is checked by `CpAc11`, `CpAc74`, `CpAc75`, `CpAc90` and
the `ArchitecturalPseudonymisationConstraintTest`.

## The three keys

| Key | Property | Protects |
|---|---|---|
| Pseudonymisation key | `pseudonym.key` | makes pseudonyms unguessable and stable (HMAC-SHA256) |
| Directory key | `pseudonym.directory-key` | seals each identity in the pseudonym directory (AES-256-GCM) |
| Intake key | `intake.encryption-key` | encrypts each bundle in the intake queue, which still holds raw identities |

Each must be base64, at least 32 bytes. The application refuses to start if one is missing or short, and
if the directory key equals either of the others, so that one leak does not expose both.

**Handling.**
- Today they come from configuration (environment variables). That is the interim source; the Key Vault
  binding is written against a port but not connected. Put them in a secret store and inject them; do not
  commit them or put them in an image.
- They are never logged: the key types print as redacted, so do the configuration records that hold them,
  and `SecretsNeverLeakTest` runs the application with recognisable keys and asserts none appears in its
  output, its responses, or any database table, in plain, base64 or hex form.
- `ReleaseChecklistTest` fails the build if a resource file looks like it holds a key. Test keys are built
  in code.

**Loss and rotation. There is no rotation procedure yet; know what each change does.**
- *Changing the pseudonymisation key* gives every contributor a new pseudonym. History is not rewritten, so
  trends that follow a contributor break at the change. Old pseudonyms stay in the directory.
- *Losing or changing the directory key* makes every stored identity unreadable. Reads then fail loudly; they
  do not look like "unknown". Back it up apart from the database.
- *Changing the intake key* leaves bundles already in the queue undecryptable. Drain the queue first.

## Re-identification

The pseudonym directory is the one place a pseudonym turns back into a person, so access is a closed list.
Only `IdentityPseudonymiser` (writes) and `ReIdentificationService` (reads) may touch it, and the two
configuration classes that build them. An ArchUnit rule checks this across every module, and no migration
other than the directory's own may mention its table.

`ReIdentificationService` needs the `REIDENTIFICATION` authority **and** a stated justification. Every
attempt, refused ones included, is written to the durable audit trail **before** anything is disclosed, and
if that write fails nothing is disclosed. The trail records who, which pseudonym, why, when and the
outcome; never the identity it revealed.

**Today there is no way to call it.** No endpoint exposes re-identification and nothing in the application
calls the service, so the `REIDENTIFICATION` trail is empty. That is deliberate: it waits on an agreed
procedure. Until then the procedure below is the intended design, not something you can run.

Intended procedure:
1. A named person with a legitimate need (a conduct or security investigation, a data-subject request)
   makes a written request to the data-protection owner, stating the pseudonym and the reason.
2. The data-protection owner approves it and grants the `REIDENTIFICATION` authority for that request only.
3. The request is made through the audited service, with the reason as the justification.
4. The audit entry is reviewed by an auditor (`ControlPlane.Auditor`, `GET /api/v1/audit/REIDENTIFICATION`).

## Retention

| Data | Kept | Then |
|---|---|---|
| Evidence detail | 30 days (`retention.evidence-hot-ttl`) | summarised into the warm tier |
| Intake queue: finished items | 1 day | deleted |
| Intake queue: dead bundles (encrypted) | 7 days | deleted |
| Audit trail | 365 days by default, never under 90 | deleted by the sweep |
| Pseudonym directory | never purged | so an old audit entry can still be resolved |

## Who may see what

See [serve layer](serve-layer.md#who-may-see-what). In short: leaders see teams, squads see their own
repositories, evidence needs its own role and a stated purpose and is recorded, auditors see the trails.
Access is checked in the services, not only the controllers.

## Known limits

- The directory shares the application's database. It is confined by architecture rules rather than a
  separate database with separate credentials.
- Free text from a repository (a message, a path) is carried as written. Nothing scans it for a name.
- Evidence from an engine whose keys have not been reviewed is withheld, not stored, until its keys are added.
