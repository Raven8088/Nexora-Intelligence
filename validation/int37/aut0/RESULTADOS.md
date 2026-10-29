# INT-3.7 — AUT-0: pruebas aisladas de los scripts de preparación

**Entorno aislado de Claude, 2026-10-28. NO es una verificación en Windows.** No se tocó tu equipo: ni Keycloak, ni credenciales, ni bases de datos, ni servicios. Tampoco se usó Git.

## Montaje

| Componente | En AUT-0 | Diferencia con Windows |
|---|---|---|
| Semantic, Reports, Discovery | Los jars de `target/` de tu equipo (copiados en solo lectura; Discovery del 19 oct, Reports del 21 oct, Semantic del 28 oct) | Ninguna funcional |
| Intelligence | Compilado desde el código de INT-3.6 (idéntico al de tu carpeta) | Ninguna funcional |
| PostgreSQL 17.6 | Dos contenedores desechables (5433 y 5434). La imagen del entorno aislado no ejecuta los scripts de `docker/postgres/init`, así que se reprodujeron sus sentencias en `run-aut0.sh`. Tampoco trae `psql`: `docker-shim.sh` ejecuta el mismo `psql` por TCP | `docker exec … psql` dentro del contenedor real queda por probar en Windows |
| Keycloak 26.4 | **Simulador** (`keycloak_sim.py`): importa el `nexora-realm.json` real con sustitución `${ENV}`, firma RS256 con JWKS, aplica los mappers del realm (audiencia, atributo heredado del grupo, roles con composites, claim fijo) e implementa la API de administración que usan los scripts | El Keycloak real puede diferir (perfil de usuario, scopes integrados, atributos, claves) |
| Ollama | **Simulador** (`llm_sim.py`): respuestas deterministas a P1–P3 con los alias del catálogo | Falta comprobar la interpretación de `gpt-oss:20b` |
| Secretos y UUID | Ficticios, solo del entorno aislado (los de `run-aut0.sh` no son válidos en ningún sistema) | — |

## Ejecución limpia (`run-aut0.sh`, salida completa en `steps.log`)

Estado inicial reproducido:

- tenant incoherente: Keycloak y `.env` con un UUID, Semantic con otro;
- `KC_DEV_USER_PASSWORD` = `KC_REPORTS_CLIENT_SECRET`, de 8 caracteres;
- almacén de secretos vacío.

Los códigos de salida 1 de V0 son los esperados: el script detecta los problemas del estado inicial.

| Paso | Códigos de salida | Comprobaciones |
|---|---|---|
| V1 (sandbox) Intelligence: mvn -o clean verify -> see verify.log | 0 | tests=517 failures=0 errors=0 skipped=3 |
| V0 Inspect (estado inicial: tenant incoherente) | 1 | [FALLO] Incoherente. Resolución (README, paso 3): |
| V0 Credenciales -Check (estado inicial) | 1 | [FALLO] semantic:KC_DEV_USER_PASSWORD is shorter than 24 characters<br>[FALLO] semantic:KC_REPORTS_CLIENT_SECRET is shorter than 24 characters<br>[FALLO] semantic:KC_DEV_USER_PASSWORD and semantic:KC_REPORTS_CLIENT_SECRET share the same value<br>[FALLO] KC_DEV_USER_PASSWORD and KC_REPORTS_CLIENT_SECRET were exposed on 29 Oct 2026 and have not been rotated with this script |
| Paso 3 -Rotate -WhatIf | 0 |  |
| Paso 3 -Rotate | 0 |  |
| Paso 3 -SetTenantDemoId <UUID de Semantic> | 0 |  |
| Paso 3 -InitializeStore | 0 |  |
| Paso 3 recrear Keycloak (simulador reiniciado: reimporta el realm con el .env nuevo) | — |  |
| Paso 3 -Inspect | 0 |  |
| Paso 3 -Check | 0 |  |
| Paso 4 -Apply -WhatIf | 0 |  |
| Paso 4 -Apply | 0 |  |
| Paso 4 -Apply (idempotencia) | 0 |  |
| V2 -Verify | 0 |  |
| Paso 6 base sintética -WhatIf | 0 |  |
| Paso 6 base sintética | 0 |  |
| Paso 6 base sintética (idempotencia) | 0 |  |
| V3 SQL de referencia | 0 | V3.P1 referencia = esperado<br>V3.P2 referencia = esperado<br>V3.P3 referencia = esperado |
| Paso 8 Discovery | — |  |
| V4 catálogo -WhatIf | 0 |  |
| V4 catálogo | 0 |  |
| V4 catálogo (idempotencia) | 0 |  |
| Paso 10 Reports | — |  |
| V2 -Verify con Reports arrancado | 0 |  |
| V5 -Manual (sin Ollama) | 0 | V5.P1 plan + ejecución = esperado<br>V5.P2 plan + ejecución = esperado<br>V5.P3 plan + ejecución = esperado |
| Paso 12 Intelligence | — |  |
| V6 -Api | 0 | V6.P1 pregunta = esperado<br>V6.P2 pregunta = esperado<br>V6.P3 pregunta = esperado<br>V6.N1 alias desconocido -> 400 validation_failed<br>V6.N2 JSON mal formado -> 400 malformed_request |
| V7 -Repeat 5 | 0 | V7.P1 5 repeticiones: filas correctas en todas<br>V7.P2 5 repeticiones: filas correctas en todas<br>V7.P3 5 repeticiones: filas correctas en todas |
| Recrear Keycloak sin reiniciar los servicios: -Apply, -Verify, V5 | 0, 0, 1 | [FALLO] V5.P1 plan + ejecución = esperado :: execution P1 -> HTTP 502 code=service_identity_invalid (expected 200)<br>V5.P2 plan + ejecución = esperado<br>V5.P3 plan + ejecución = esperado |
| -Revert | 0 |  |
| -Inspect tras -Revert | 0 |  |
| Negativo: variable de entorno obsoleta KC_DEV_USER_PASSWORD -> -Check debe fallar | 1 | [FALLO] environment variable KC_DEV_USER_PASSWORD is set and differs from semantic:KC_DEV_USER_PASSWORD: remove it from this session (Remove-Item Env:KC_DEV_USER_PASSWORD) |

Después de recrear Keycloak y reiniciar Semantic, Reports e Intelligence, como indica ahora el README, V5 y V6 pasan completas (`restauracion-con-reinicio.log`). La huella de cada plan obtenido por la API es igual a la del plan manual. Por tanto, P3 por la API produce exactamente el intervalo [2026-10-01T00:00, 2026-10-08T00:00).

## Pruebas negativas (`negativos.log`, pasada anterior con la misma lógica)

| Caso | Resultado |
|---|---|
| `-Revert -WhatIf` y `-Revert` | Solo se eliminan los objetos marcados INT-3.7 |
| `demo.editor` existente sin marca | `-Apply` se niega; `-Revert` no lo toca |
| `REPORT_RUNNER` previo sin marca | `-Apply` lo reutiliza; `-Revert` lo conserva |
| `KC_TENANT_DEMO_ID` incoherente | `-Apply` se niega |
| Un secreto del almacén igual a `KC_DEV_USER_PASSWORD` | `-Apply` se niega (solo muestra nombres) |
| Falta el secreto del lector | `Initialize-Int37SyntheticDb.ps1` se niega |
| Secreto de `nexora-intelligence` cambiado en el almacén | `-Apply` actualiza el cliente; `-Verify` pasa |
| Puerto ocupado | `Start-Int37Service.ps1` se niega a arrancar |
| Variable de entorno antigua `KC_DEV_USER_PASSWORD` | `-Check` falla y explica cómo quitarla |
| Lector de la base sintética | Puede leer; no puede escribir ni crear objetos (lectura obligatoria y sin privilegios); sin acceso a `nexora_reports` ni a `synthetic_erp` |

## Seguridad

Se buscaron en todos los registros, informes, `state.json` y `application-int37.yml` del entorno aislado los 13 valores secretos vigentes y cualquier JWT: **0 coincidencias**. `secrets.env` queda con permisos 600 y su carpeta con 700.

## Problemas encontrados y corregidos

| # | Problema | Corrección | ¿Afectaba a Windows? |
|---|---|---|---|
| 1 | `Find-Client`/`Find-User` trataban mal una lista de un elemento (PowerShell desenrolla los arrays devueltos): no encontraban `nexora-semantic-dev` ni los usuarios | `@(...)` en ambas búsquedas | **Sí**: `-Inspect` y `-Apply` habrían fallado |
| 2 | Una variable de entorno con el nombre de un secreto anula en silencio el `.env` o el almacén (detectado al rotar) | `-Check` la detecta (falla si el valor es distinto) | Sí, si la sesión conserva variables antiguas |
| 3 | Tras recrear Keycloak, los servicios con un token en caché fallan una vez (Reports: `502 service_identity_invalid`) | README: reiniciar Semantic, Reports e Intelligence después de recrear | Sí (comportamiento de los servicios, no de los scripts) |
| 4 | `-Inspect` terminaba con código 0 aunque el tenant fuera incoherente | Ahora termina con 1 | Mejora |
| 5 | Fecha de rotación mostrada en formato local | Formato ISO | Estético |
| 6 | Rutas relativas con barra invertida | Rutas con `/` (válidas en Windows y Linux) | No |

## Pendiente para Windows

- AUT-1 a AUT-5 con el Keycloak 26.4 real y tus servicios.
- `docker exec … psql` en el contenedor real.
- `icacls` en Windows.
- `gpt-oss:20b` real en V6 y V7.
- INT-3.5 e INT-3.6: `mvn clean verify` en Windows. En el entorno aislado, Intelligence da 517 pruebas, 0 fallos, 0 errores y 3 omitidas (sin Ollama).
