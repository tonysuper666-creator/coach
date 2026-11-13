#!/bin/bash
set -eu
cd "$(dirname "$0")/../../.."
fixture="${TMPDIR:-/tmp}/reader-anchor-$(uuidgen)"
mkdir "$fixture"
swiftc -parse-as-library ios/ReaderExperience/Sources/ReaderExperience/ReaderState.swift ios/ReaderExperience/Sources/ReaderExperience/FileReaderDraftStore.swift ios/ReaderExperience/Host/AnchorProcessCheck.swift -o "$fixture/check"
"$fixture/check" write "$fixture/draft.json"
"$fixture/check" read "$fixture/draft.json"
printf 'Synthetic UUID fixture retained: %s\n' "${fixture##*/}"
