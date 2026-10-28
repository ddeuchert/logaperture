# `logctl` against a service running as another account

Status: **Draft, not signed off.** Targets `1.0.0`. No freeze exception is needed yet: if open
question 1 shows that attach from an elevated Administrator prompt already works, the 1.0.0 work is
a guide page, a corrected "same user" statement and a `doctor` hint, which the beta.1 feature freeze
(§17.1) allows. Only if it fails, and the fix is a new access mechanism, does the maintainer decide
between an exception for 1.0.0 (as #210 had) and 1.1.0.

Tracking issue: [#287](https://github.com/ddeuchert/logaperture/issues/287) (milestone `1.0.0`;
branches from and targets `release/1.0`, see [`release-lines.md`](release-lines.md)).

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §8.1 (the CLI over the Attach
API), §8.2 (the agent never listens), §9.8 (authentication comes from the operating system), §16
(constrained and customer-hosted environments).
Builds on: [`cli-transport.md`](cli-transport.md) (the attach transport),
[`doctor.md`](doctor.md). Related, on `develop` for 1.1.0 and not yet on this line: `logctl attach`
(#273), whose refusal for "a JVM owned by another user" would share this feature's ownership rule.

## Functional summary

After this feature, the user will be able to:

- Run `logctl` from an Administrator prompt against an application server that runs as a Windows
  service under `LocalSystem` (or another service account), the way they can already run
  `jboss-cli.bat`, without first becoming that account.
- Be told plainly, when `logctl` cannot reach a JVM because it runs as another account, which
  account that is and what to do about it.
- Read a guide page that shows how to run `logctl` as a service account when that is the only way.

Exactly which of these needs new behaviour depends on open question 1.

Guide pages this changes (when it lands): `troubleshooting.md` (the "another user" message);
`concepts/security.md` (who may control a JVM); a new `how-to/` page for service accounts on
Windows; `reference/logctl.md` if the help changes; the changelog.

## Motivation

A customer runs WildFly as a Windows service under `LocalSystem`. Their administrators have an
Administrator prompt, not a SYSTEM shell, and Sysinternals PsExec is not installed. `jboss-cli.bat`
works for them because it connects to the management port (`localhost:9990`) and WildFly's local
authentication trusts anyone who can read the token file under `standalone\tmp\auth`, which
Administrators can. `logctl` is meant to replace that path for logging changes, but today it
attaches by process id, and the spec and guide say it needs the same account as the JVM
(§9.8, `how-to/try-without-restart.md`: "Same machine, same user").

The workaround that exists today is a one-off scheduled task running as SYSTEM that runs
`logctl` and writes its output to a file. It works for non-interactive commands only and is not
something to ask a customer to do during an incident.

## Open design questions (deferred to when this is specced)

Nothing below is decided.

1. **Does it already work?** Test first, before designing anything: from an elevated Administrator
   prompt, does `VirtualMachine.attach` reach a JVM running as a `LocalSystem` service? The
   Windows attach rules are not verified in this project (the guide lists Windows attach as
   untested), and the answer changes the scope. If it works, this feature shrinks to the guide
   page, a corrected "same user" statement and a `doctor` hint, and questions 2 to 5 fall away. If
   it does not, they apply.
2. **What grants access without matching the owner?** Candidates, to be named and compared, not
   chosen: a token file readable by Administrators (WildFly's local authentication is the model);
   the JVM's own `com.sun.management.jmxremote` local connector; reusing the container's
   management interface where one exists. Each has a different answer to question 3.
3. **§8.2 and §9.8.** The agent never listens, and authorization is the operating system's. Any
   mechanism that lets a different account in changes one of the two. State which, and what a
   security review would be told. Anything that opens a socket in the customer's JVM is
   presumed out unless it keeps the §8.2 claim.
4. **Who counts as an administrator.** Local Administrators, the elevated token only, or a
   configured group. Whether the answer differs on Linux (root, which attach already allows).
5. **Audit.** Whether a change made by an account other than the owner is recorded as such
   (§9.7), and whether `--reason` becomes required for it.
6. **Scope of the platform.** Windows services only, or the same story for a Linux service run by
   a dedicated account and an operator who has `sudo` but not that account.
7. **Interaction with `logctl attach`** (#273): the same ownership rule decides whether an attach
   can load the agent, so the answer must hold for both.

## Cost and dependencies

Layer 0, transport only: no new operation and no change to any MXBean signature, so the §11.1
compatibility promise is untouched. If question 1 comes back "works", the cost is documentation
and one `doctor` check. Otherwise it is new surface in both the agent and `logctl`, which is the
reason it is a poor fit for the freeze and the reason an exception would need agreeing.

Needs a real Windows service run as `LocalSystem` to test; the CI matrix has none, and the
dev WildFly environment is Linux.
