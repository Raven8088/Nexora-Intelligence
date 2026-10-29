#!/bin/bash
# AUT-0 sandbox shim. The sandbox postgres image has no psql inside, so
#   docker exec [-e VAR]... [-i] <container> psql ARGS
# runs the HOST psql instead:
#   - with "-h <address>" (TCP inside the container)  -> TCP to the container's published port on 127.0.0.1
#     (the server sees the Docker gateway as source, so pg_hba applies scram-sha-256, as for a non-loopback address);
#   - without -h (local socket of the container)       -> the container's Unix socket, mounted on the host
#     (pg_hba "local" rules apply: trust), exactly the passwordless recovery path.
# -e VAR passes the variable from this process (psql inherits it). Everything else goes to the real docker CLI.
if [ "$1" = "exec" ]; then
  shift
  while [ "$1" = "-e" ]; do shift 2; done
  [ "$1" = "-i" ] && shift
  c="$1"; shift
  if [ "$1" = "psql" ]; then
    shift
    case "$c" in nexora-reports-postgres) port=5434; sock=/work/aut0/pgsock/reports;; nexora-semantic-dev-postgres-1|semantic-postgres) port=5433; sock=/work/aut0/pgsock/semantic;; *) echo "shim: unknown container $c" >&2; exit 2;; esac
    if [ -n "${SHIM_DELAY:-}" ]; then sleep "$SHIM_DELAY"; fi
    args=(); tcp=0
    while [ $# -gt 0 ]; do
      case "$1" in
        -h) tcp=1; args+=("-h" "127.0.0.1"); shift 2;;
        -p) args+=("-p" "$port"); shift 2;;
        *) args+=("$1"); shift;;
      esac
    done
    if [ $tcp = 1 ]; then exec psql "${args[@]}"; else exec psql -h "$sock" -p 5432 "${args[@]}"; fi
  fi
  echo "shim: unsupported exec" >&2; exit 2
fi
exec /usr/bin/docker "$@"
