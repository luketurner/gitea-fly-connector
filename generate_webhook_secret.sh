#!/usr/bin/env bash
set -euo pipefail

WEBHOOK_SECRET="$(openssl rand -hex 12)"
fly secrets set "GFC_WEBHOOK_SECRET=$WEBHOOK_SECRET"

echo "Configure Gitea webhook with secret: $WEBHOOK_SECRET"
