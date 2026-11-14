#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Starts and stops the local Mumble test server for the end-to-end tests.
#
# The server is the test environment of the Mumcord repository (testenv/: a stock, unmodified
# mumble-server in Docker with the channels Lobby, Raid and AFK), taken at the engine commit
# pinned in engine.lock.toml and checked out into the git-ignored .engine/.
#
# Usage: scripts/testenv.sh <fetch|up|down>
#   fetch   check out testenv/ of the pinned Mumcord commit into .engine/
#   up      fetch, then start the server and wait until it accepts connections
#   down    stop the server and delete its data, so the next start is a fresh server
#
# Environment:
#   MUMCORD_PORT_BASE   host port of the server (default 64838, so a test server of the desktop
#                       client on 64738 can run next to it)
#   MUMCORD_PROJECT     Docker Compose project name (default mumcord-mobile-e2e)
#
# Needs: git, docker with the compose plugin, sqlite3, openssl.
set -euo pipefail

export MUMCORD_PORT_BASE="${MUMCORD_PORT_BASE:-64838}"
export MUMCORD_PROJECT="${MUMCORD_PROJECT:-mumcord-mobile-e2e}"
# Seconds to wait for the started server to accept connections.
READY_TIMEOUT=60
# Folders of the Mumcord repository that are checked out; the whole tree is some 300 MB.
ENGINE_PATHS=(testenv)

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_DIR="${REPO_ROOT}/.engine"
TESTENV_DIR="${ENGINE_DIR}/testenv"
LOCK_FILE="${REPO_ROOT}/engine.lock.toml"

die() {
  printf 'testenv: %s\n' "$*" >&2
  exit 1
}

need() {
  command -v "$1" >/dev/null 2>&1 || die "$1 is required but not installed"
}

# Value of a key in engine.lock.toml, empty if the key is not set.
lock_value() {
  sed -n "s/^$1 *= *\"\(.*\)\" *\$/\1/p" "${LOCK_FILE}" | head -n 1
}

fetch() {
  need git
  local repository rev tag ref
  repository="$(lock_value repository)"
  rev="$(lock_value rev)"
  tag="$(lock_value tag)"
  [[ -n "${repository}" ]] || die "no repository in ${LOCK_FILE}"
  if [[ -n "${rev}" && -z "${tag}" ]]; then
    ref="${rev}"
  elif [[ -n "${tag}" && -z "${rev}" ]]; then
    ref="refs/tags/${tag}"
  else
    die "${LOCK_FILE} must set exactly one of rev and tag"
  fi

  local stamp="${ENGINE_DIR}/.fetched-ref"
  if [[ -f "${stamp}" && "$(cat "${stamp}")" == "${repository} ${ref}" && -d "${TESTENV_DIR}" ]]; then
    return
  fi

  # --git-dir is given explicitly so that git can never fall back to the mobile repository.
  local git=(git --git-dir="${ENGINE_DIR}/.git" --work-tree="${ENGINE_DIR}")
  mkdir -p "${ENGINE_DIR}"
  rm -f "${stamp}"
  [[ -d "${ENGINE_DIR}/.git" ]] || git init --quiet "${ENGINE_DIR}"
  "${git[@]}" config core.sparseCheckout true
  "${git[@]}" config core.sparseCheckoutCone true
  mkdir -p "${ENGINE_DIR}/.git/info"
  printf '/*\n!/*/\n' >"${ENGINE_DIR}/.git/info/sparse-checkout"
  local path
  for path in "${ENGINE_PATHS[@]}"; do
    printf '/%s/\n' "${path}" >>"${ENGINE_DIR}/.git/info/sparse-checkout"
  done
  if ! "${git[@]}" fetch --quiet --depth 1 --filter=blob:none "${repository}" "${ref}"; then
    die "could not fetch ${ref} from ${repository}.
If the network is fine, the repository no longer serves this commit: it was on a feature branch
that has been rebased or deleted. Set engine.lock.toml (and the bridge dependency in Cargo.toml)
to a commit that exists on the engine branch, or to a release tag."
  fi
  "${git[@]}" checkout --quiet --force --detach FETCH_HEAD
  [[ -x "${TESTENV_DIR}/up.sh" ]] || die "${ref} of ${repository} has no testenv/up.sh"
  printf '%s %s\n' "${repository}" "${ref}" >"${stamp}"
  printf 'testenv: checked out %s into .engine/\n' "${ref}"
}

compose_environment() {
  # Relative entries of COMPOSE_FILE are resolved in testenv/, where the scripts run compose.
  export COMPOSE_FILE="docker-compose.yml:${REPO_ROOT}/e2e/compose.bridge.yml"
}

port_open() {
  (exec 3<>"/dev/tcp/127.0.0.1/${MUMCORD_PORT_BASE}") 2>/dev/null
}

up() {
  need docker
  need sqlite3
  need openssl
  docker compose version >/dev/null 2>&1 || die "the Docker compose plugin is required"
  fetch
  if [[ ! -f "${TESTENV_DIR}/.env" ]]; then
    # A throwaway password for a throwaway local server; never committed (.engine/ is ignored).
    (
      umask 077
      printf 'SUPERUSER_PASSWORD=%s\nSERVER_PASSWORD=\n' "$(openssl rand -hex 16)" >"${TESTENV_DIR}/.env"
    )
  fi
  compose_environment
  "${TESTENV_DIR}/up.sh" v15

  local waited=0
  until port_open; do
    if ((waited >= READY_TIMEOUT)); then
      (cd "${TESTENV_DIR}" && docker compose logs --tail 30 v15 >&2) || true
      die "the server did not accept connections on port ${MUMCORD_PORT_BASE} within ${READY_TIMEOUT} s"
    fi
    sleep 1
    waited=$((waited + 1))
  done
  printf 'testenv: server ready on port %s\n' "${MUMCORD_PORT_BASE}"
}

down() {
  [[ -d "${TESTENV_DIR}" ]] || return 0
  need docker
  compose_environment
  "${TESTENV_DIR}/down.sh"
  # A fresh server for every run: users and settings of one run must not leak into the next.
  rm -rf "${TESTENV_DIR}/data/_${MUMCORD_PROJECT}"
}

case "${1:-}" in
  fetch) fetch ;;
  up) up ;;
  down) down ;;
  *)
    printf 'usage: %s <fetch|up|down>\n' "$0" >&2
    exit 2
    ;;
esac
