#!/bin/bash
# AUT-0 sandbox shim. The sandbox postgres image has no psql inside, so
#   docker exec [-e VAR]... -i <container> psql ARGS
# runs the host psql over TCP against the container's published port (same SQL, same psql flags).
# Everything else goes to the real docker CLI.
if [ "$1" = "exec" ]; then
  shift
  while [ "$1" = "-e" ]; do shift 2; done
  [ "$1" = "-i" ] && shift
  c="$1"; shift
  if [ "$1" = "psql" ]; then
    shift
    case "$c" in nexora-reports-postgres) port=5434;; semantic-postgres) port=5433;; *) echo "shim: unknown container $c" >&2; exit 2;; esac
    export PGPASSWORD=$(grep '^REPORTS_DB_OWNER_PASSWORD=' /work/aut0/NexoraAI/Nexora-Reports/docker/.env | cut -d= -f2)
    exec psql -h 127.0.0.1 -p "$port" "$@"
  fi
  echo "shim: unsupported exec" >&2; exit 2
fi
exec /usr/bin/docker "$@"
