#!/usr/bin/env bash
# Fails if anything that belongs to the private overlay, a credential, or agent context is tracked.
# Public stand-ins under app/src/oss/ are allowed.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
deny='^private/|^app/src/main/cpp/parked/|^app/src/main/cpp/secrets/|PrivilegedShellSetup\.kt$|cluster_carrier/|NavigatorMapDisplay|(^|/)\.agents/|(^|/)\.kiro/|(^|/)\.claude/|\.jks$|\.keystore$|safekeep-oauth\.properties$|firebase-service-account|official\.properties$'
private_feature='customlayout|custom_layout|AaosNavigationPresentation|dashboard-builder|instrument_design|strings_dashboard_instruments'
bad=$(git ls-files | grep -E "$deny" || true)
bad+=$'\n'$(git ls-files | grep -iE "$private_feature" | grep -v '^app/src/oss/' || true)
bad=$(printf '%s\n' "$bad" | sed '/^$/d')
if [ -n "$bad" ]; then
  echo "Private or secret paths are tracked:"; printf '  %s\n' $bad; exit 1
fi
# Known secret markers in tracked text files (a PEM header counts only with a key body after it).
hits=$(git grep -nIE 'SriPass|"private_key_id"' -- . ':!tools/ci/check-private-paths.sh' || true)
hits+=$(git grep -nIE -A1 'BEGIN (RSA |EC )?PRIVATE KEY-----' -- . ':!tools/ci/check-private-paths.sh' | grep -E -- '-[0-9]+-[[:space:]"]*[A-Za-z0-9+/]{60,}' || true)
if [ -n "$hits" ]; then echo "Secret markers found:"; printf '%s\n' "$hits"; exit 1; fi
echo "private-path guard: OK"
