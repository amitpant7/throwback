#!/usr/bin/env bash
# Lints the page script as the build joins it (one function, ES5): undefined names, unused names,
# redeclarations, unreachable code. Needs Node and network access the first time (npx fetches ESLint).
# Usage: tools/pagescript-lint.sh   (from the repo root)
set -eu
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
cat app/src/main/pagescript/*.js | sed 's/__THROWBACK_CONFIG__/{}/' > "$OUT/throwback.js"
npx --yes eslint@8.57.0 --no-eslintrc --env browser --parser-options=ecmaVersion:5 \
  --global WeakMap,Set,Map,Promise,Uint8Array,PerformanceObserver,IntersectionObserver,URLSearchParams,Response,NodeFilter,PopStateEvent \
  --rule 'no-undef:error' --rule 'no-unused-vars:[error,{"args":"none"}]' --rule 'no-redeclare:error' \
  --rule 'no-dupe-keys:error' --rule 'no-unreachable:error' --rule 'no-self-assign:error' --rule 'eqeqeq:[error,"smart"]' \
  "$OUT/throwback.js"
echo "page script: clean"
