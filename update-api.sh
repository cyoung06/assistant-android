#!/bin/sh
# Copies the API contract from the API repo; the client is generated from it at build time.
cp "$(dirname "$0")/../api/openapi.json" "$(dirname "$0")/api/openapi.json"
