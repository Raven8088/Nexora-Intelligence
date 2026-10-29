# AUT-1b — Pruebas aisladas de la rotación PostgreSQL

Entorno aislado de Claude, 29 de octubre de 2026. **Credenciales ficticias. No es una verificación en Windows.** No se ha tocado ninguna base, contenedor, servicio ni `.env` del equipo del usuario.

## Pruebas sin Docker

`pruebas-unitarias.log`: 106 comprobaciones, 0 fallos.

- `Int37AdminSession.Tests.ps1`: 24
- `Int37PgRotation.Tests.ps1`: 42 (nuevas)
- `Int37Rotation.Tests.ps1`: 40

## Integración contra PostgreSQL 17.6 real

`steps.log` (salida completa) y `run-aut1b.sh` (reproducción).

- **Contenedores**: `postgres:17.6` desechables, con los mismos nombres y puertos que en Windows (`nexora-semantic-dev-postgres-1` en 5433, `nexora-reports-postgres` en 5434).
- **Roles**: creados como en los `init` de Semantic y Reports.
- **Credenciales**: las nueve con el mismo valor de 4 caracteres.
- **Aplicaciones**: Semantic y Reports, con los jars del equipo del usuario. Keycloak, simulado.

| # | Prueba | Resultado |
|---|---|---|
| 1 | `pg_hba` real | `local all all trust`; `host all all all scram-sha-256`; `password_encryption=scram-sha-256` |
| 2 | `-WhatIf` (Semantic y Reports) | Plan con nombres; nada cambia; sin diario |
| 3 | `-RotatePostgres -Target Semantic` | 3 roles actualizados y verificados por TCP; `.env` a 32 caracteres; el valor anterior ya no entra; diario `COMPLETED` |
| 4 | Semantic arrancado durante la rotación | Sigue `UP`. Tras reiniciarlo, Flyway valida 9 migraciones; `GET` responde 200 y `POST` 201 con el pool nuevo |
| 5 | `-RotatePostgres -Target Reports` | Ídem; Reports arranca (`Started ReportsApplication`) con los valores nuevos |
| 6 | `-Check` tras AUT-1b | Solo quedan las credenciales de Keycloak y el almacén |
| 7 | `kill -9` tras cambiar `nexora_semantic_owner` | `.env` sin cambiar; app y propietario desincronizados; diario `APPLYING` |
| 8 | `-Check`, una nueva rotación y `-Recover` | `-Check` lo señala; la rotación se niega; `-Recover` deja los 3 roles con el `.env` (`RECOVERED`); repetirlo no hace nada |
| 9 | `kill -9` tras cambiar el superusuario de Reports | Los 3 roles desincronizados; `-Recover` entra por el *socket* sin la contraseña nueva y lo resincroniza |
| 10 | Rotación completa después de recuperar | Correcta en los dos destinos |
| 11 | AUT-1a (`-Rotate`) + AUT-1b ×2 + `-InitializeStore` | `-Check`: «Sin problemas» (exit 0) |
| 12 | `pg_hba` con `host all all all trust` | Se niega antes de tocar nada |
| 13 | Contenedor detenido | Se niega; el `.env` no cambia |
| 14 | Búsqueda de los 16 valores de secretos en las 13 salidas, registros y diario | 0 apariciones |

## Adaptador (`docker-shim.sh`)

La imagen del entorno aislado no trae `psql` ni ejecuta los `init`. El adaptador sustituye `docker exec … psql` por el `psql` del anfitrión:

- con `-h`, por TCP al puerto publicado (el servidor ve la puerta de enlace de Docker: regla `host … scram-sha-256`);
- sin `-h`, por el *socket* del contenedor montado en el anfitrión (regla `local … trust`).

En Windows, `psql` se ejecuta dentro del contenedor oficial. Queda por comprobar allí.

## Incidencias del montaje (no del script)

- Esa imagen usa `/tmp` para el *socket* e ignora `POSTGRES_HOST_AUTH_METHOD`. La prueba 12 reescribe `pg_hba.conf` por el *socket* y lo recarga.
- Una primera versión del driver capturaba el PID del *subshell* en lugar del de `pwsh`. Corregido con `exec`: en la versión final, el proceso muere de verdad.

## Corrección del script

La prueba de contraseña falsa se hace para cada rol, no solo para el primero, porque una regla `pg_hba` puede confiar en un único usuario. Tiene una prueba nueva.
