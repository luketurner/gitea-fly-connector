#!/usr/bin/env bash
set -eEuo pipefail

SSH_HOST="$1"
TMPDIR = "$(mktemp -d)"

cleanup() {
  echo "Deleting temporary directory..."
  rm -r "$TMPDIR"
  if [[ $? == 0 ]]; then
    echo "Script succeeded!"
  else
    echo "Script failed :("
  fi
}

trap cleanup EXIT

# Note -- the fingerprint and host keys aren't secrets,
# but including them in secrets so this script can manage everything.

echo "Generating SSH Deploy Keys..."
ssh-keygen -t ed25519 -f "$TMPDIR/gfc_deploy" -N "" -C "deploy@gitea-fly-connector"
SSH_FINGERPRINT="$(ssh-keygen -lf "$TMPDIR/gfc_deploy")"

if [[ ! "$SSH_HOST" ]]; then
  echo "No SSH host specified -- skipping GFC_SSH_ALLOWED_HOSTS setup. See README.md for usage."
  fly secrets set \
    "GFC_SSH_PRIVATE_KEY=$(base64 -w0 "$TMPDIR/gfc_deploy")" \
    "GFC_SSH_FINGERPRINT=$SSH_FINGERPRINT"
else
  fly secrets set \
    "GFC_SSH_PRIVATE_KEY=$(base64 -w0 "$TMPDIR/gfc_deploy")" \
    "GFC_SSH_FINGERPRINT=$SSH_FINGERPRINT" \
    "GFC_SSH_ALLOWED_HOSTS=$(ssh-keyscan "$SSH_HOST" | base64 -w0)"
fi

echo "Generated SSH key fingerprint: $SSH_FINGERPRINT"

echo
echo "Configure Gitea Deploy Key with public key:"
echo "$(cat "$TMPDIR/gfc_deploy.pub")"