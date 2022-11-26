#!/usr/bin/env bash
set -eEuo pipefail

SSH_HOST="$1"

fly secrets set "GFC_SSH_ALLOWED_HOSTS=$(ssh-keyscan "$SSH_HOST" | base64 -w0)"