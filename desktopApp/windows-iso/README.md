# Windows book isolation prototype

This directory contains an experimental AppContainer launcher. It is not wired into the application or a production security boundary. It still contains a negative-control switch and needs independent security review before use.

A future Windows book transport must launch the CLI only through a verified container, grant only the per-request work, executable and isolated authentication directories, and pass the excerpt through standard input. Treat launcher exit codes 120–129, a missing or invalid verification receipt, timeout, or any other failure as unavailable: do not return an answer and do not fall back to launching the CLI without the container. The existing `LocalCodexBookTransport` integration is intentionally untouched.

A synthetic authenticated CLI call has run in an interactive AppContainer. Safe authentication refresh/writeback, model-initiated denial, cancellation, and production cleanup still require end-to-end verification. The negative-control switch and unchecked Job API results must be addressed before integration. Never grant the real book library or the user's full CLI home to the container.
