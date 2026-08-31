#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ "${1:-}" == --test-* ]]; then
  # The --test-* entry points live in the test suite, and get-swarm-forge
  # installs swarmforge/scripts without ever copying test/. So in an installed
  # swarmforge/ this path resolves to a file that is not there. Say which flag
  # needs what, instead of letting bb report a bare "File does not exist" for a
  # path the caller never typed. pack_web.bb still lists these flags in its
  # usage text, so an installed copy will send people down this branch.
  REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
  HARNESS="$REPO_ROOT/test/swarmforge/pack_web_test.bb"
  if [[ ! -f "$HARNESS" ]]; then
    echo "$1 is a test entry point and needs a SwarmForge checkout." >&2
    echo "  expected: $HARNESS" >&2
    echo "An installed swarmforge/ has no test/ directory. Run --test-* flags from a clone." >&2
    exit 1
  fi
  exec bb "$HARNESS" "$@"
else
  exec bb "$SCRIPT_DIR/pack_web.bb" "$@"
fi
