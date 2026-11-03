# Disposable launchd rehearsal

This adapter controls only private `/tmp/ton1175-*` directories, random
`local.coach.ton1175.test.<uuid>` jobs in the current user's launchd domain,
and ports other than 8790, 8791, 18791 and 3790. It refuses production labels,
external data/token/home/socket paths, managed dependencies and source lookup.
It compares live service identities with the Multica daemon before stopping a
job. Services belong to launchd (PPID 1), outside command guardian groups.

On macOS, with Java 17 and an already cached offline build:

```sh
# Read the plan; no service or build side effects.
python3 tools/dev/rehearse_ton1175_launchd.py
# Explicitly create disposable jobs and remove them before returning.
python3 tools/dev/rehearse_ton1175_launchd.py --apply --output ../launchd-evidence.json
```

The output must be a new file. Allow about 22 minutes. The script obtains the
repository's heavy-build lock, builds `:server:app:installDist --offline`, and
starts nine real JVMs with synthetic device identities and empty private data.
All nine observe real time concurrently. No injected application clock is used:
each old JVM must report at least 900 seconds of uninterrupted monotonic
coverage. A real JFR recording, process identity, heartbeats and request-log
verification pass through the existing verifier before acquiring the seal.

Before observation, a separate real bootstrap race kills the controller after
launchd creates the job and before PID registration. The persisted intent must
allow removal of that exact job. The output retains coverage/JFR proof hashes
and supervisor event records for independent inspection after cleanup.

The nine transaction scenarios are normal switch followed by rollback; a stopped worker before
the seal; worker SIGKILL while the seal is held; after actual service stop;
after pointer writes; during acceptance after a real authenticated ledger
commit; an acceptance hang that exhausts the actual 300-second reserve; a
recovery hang followed by supervised retry; and a real candidate JVM startup
failure caused by an invalid VM option. Fault hooks include a TERM-ignoring
descendant. Every registered guardian must be reaped, its process group empty,
and the launchd service must remain outside these groups.

Seal release is checked on the same old JVM before recovery restarts it.
Committed candidate `note_saved` events must survive pointer restoration and
old-service restart. Final cleanup removes every test job and its temporary
plist/data tree, including failed scenarios. The foreground script owns cleanup;
do not kill it or leave it running after the coding-agent turn exits.

The candidate verifier in this rehearsal checks the real JVM distribution's
tree hash. Old and candidate source heads must have identical `server` and
`shared` trees; these tools changes exercise a real restart across distinct
release paths without inventing an application change. Python/MathJax paths
are inert shape fixtures: `COACH_MANAGE_SERVICES=0` never executes them. This
does **not** validate production dependency provenance, original-device
authentication, tutor/model behavior, phone connectivity or a production
launchd adapter. The acceptance hook uses only synthetic authentication and a
real `save_note` command, with no bridge or model call. Production gates remain
unverified.
