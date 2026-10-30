#!/usr/bin/env bash
set -euo pipefail
# Only the bounded isolated emulation. Never deploy the AWS candidate to LocalStack.
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
pwsh -NoProfile -File "$ROOT/scripts/synth.ps1"
pwsh -NoProfile -File "$ROOT/scripts/verify-localstack.ps1"
