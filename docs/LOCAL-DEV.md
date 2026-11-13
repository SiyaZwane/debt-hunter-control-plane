# Local development

## What you need

- Java 21 (Maven comes from the wrapper: `./mvnw`).
- A Docker daemon. The integration tests start a real **PostgreSQL** with Testcontainers.
- The data plane installed in your local Maven repository (once):

```bash
git clone https://github.com/SiyaZwane/debt-hunter.git
cd debt-hunter
./mvnw -pl cli -am install -DskipTests -Djacoco.skip=true
```

That installs `domain` (the shared model) and `cli` (the runnable data-plane jar, which the integration
tests run as a separate process exactly as a pipeline would).

There is **no Kafka and no Redis** to run. The ingest queue is in PostgreSQL, and nothing uses a cache
server.

## Docker with Colima

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Building and testing

```bash
./mvnw clean verify                     # everything, including the release gate
./mvnw -pl control-plane-store -am verify   # one module and what it needs
```

Always add `-am` with `-pl`: the sibling modules are not installed in your local repository, so a single
module cannot resolve them.

Unit tests run in surefire. Integration tests (`src/integrationTest`, tagged `integration`) run in
failsafe and need Docker. To run one:

```bash
./mvnw -pl control-plane-app -am verify -Dtest='NoSuch*' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dit.test=CpAc92_ServeStopDoesNotBlockIngestTest
```

The offline-conformance gate runs on every `verify` of the application module, whatever you pass; that is
the point of it. It takes about ten seconds.

Formatting is checked by Spotless (Google Java Format). `./mvnw spotless:apply` fixes it.

## Running the application

It will not start without its mandatory configuration. See [running](running.md) for every property and
environment variable. The minimum:

| Environment variable | What |
|---|---|
| `CONTROL_PLANE_JWK_SET_URI`, `CONTROL_PLANE_JWT_ISSUER`, `CONTROL_PLANE_JWT_AUDIENCE` | how tokens are verified |
| `CONTROL_PLANE_DB_URL`, `CONTROL_PLANE_DB_USER`, `CONTROL_PLANE_DB_PASSWORD` | the system of record |
| `CONTROL_PLANE_PSEUDONYM_KEY` | base64, at least 32 bytes |
| `CONTROL_PLANE_PSEUDONYM_DIRECTORY_KEY` | base64, at least 32 bytes, different from the other keys |
| `CONTROL_PLANE_INTAKE_KEY` | base64, at least 32 bytes, different from the other keys |

Generate a key with `openssl rand -base64 32`. Use different values for each, and never reuse a key from a
real deployment locally.

## Conventions

- Tests are named for the criterion they prove: `CpAc92_...Test`, with methods `cpAc92_...`.
- `ReleaseChecklistTest` fails the build if a CP-AC or CP-BC id has no named test, if anything is
  disabled, or if a resource file looks like it holds a key.
- Mutation-check what you add: break the code the test is about and confirm the test fails.
