#!/usr/bin/env bash
set -eEuo pipefail

TMPDIR="$(mktemp -d)"

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

# Note -- the fingerprint isn't a secret,
# but including it in secrets so this script can manage everything.

echo "Generating SSH Deploy Keys..."
ssh-keygen -t ed25519 -f "$TMPDIR/gfc_deploy" -N "" -C "deploy@gitea-fly-connector"
SSH_FINGERPRINT="$(ssh-keygen -lf "$TMPDIR/gfc_deploy")"

fly secrets set \
  "GFC_SSH_PRIVATE_KEY=$(base64 -w0 "$TMPDIR/gfc_deploy")" \
  "GFC_SSH_KEY_FINGERPRINT=$SSH_FINGERPRINT"

echo
echo "Generated SSH key fingerprint: $SSH_FINGERPRINT"
echo
echo "Configure Gitea Deploy Key with public key:"
echo "$(cat "$TMPDIR/gfc_deploy.pub")"