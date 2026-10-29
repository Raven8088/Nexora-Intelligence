# Audiencia `nexora-reports` en el scope compartido: diagnóstico y corrección

Entorno aislado de Claude, 29 de octubre de 2026. Keycloak simulado, jar real de Semantic, PostgreSQL desechable y secretos ficticios. **No es una verificación en Windows.** No se ha tocado tu Keycloak.

## Causa (confirmada con tu inventario sin secretos)

`keycloak-nexora-20261029040121.json` se generó antes de `-Apply`. Ya contenía, en el *client scope* compartido `nexora-semantic`:

```
nexora-reports-audience  oidc-audience-mapper  included.custom.audience=nexora-reports  access.token.claim=true
```

Ese mapper no está en `nexora-realm.json`, ni lo crean `-Apply` ni R1. `nexora-semantic` es scope por defecto de `nexora-semantic-dev` y de `nexora-reports-dev`. Por eso todo token con ese scope recibe `aud=nexora-reports`, incluidos `demo.editor` y `demo.approver`. En tu realm, además, `steward.demo` tiene `REPORT_RUNNER`.

## Reproducción y corrección (`steps.log`, `run-aud.sh`)

El simulador incluye el mapper con la misma configuración (`SIM_SHARED_REPORTS_AUD=1`).

| # | Paso | Resultado |
|---|---|---|
| 1 | `-Inspect` | Avisa del mapper, la causa y la corrección |
| 2 | `-SharedReportsAudience Disable` antes de `-Apply` | Se niega: `nexora-intelligence` aún no tiene su propia audiencia |
| 3–4 | `-Apply`; `-Verify` | **Mismos 2 fallos que en Windows** (`demo.editor` y `demo.approver`: «aud no contiene nexora-reports»), con la indicación de la causa |
| 5 | `Disable -WhatIf` | Muestra el impacto; no cambia nada |
| 6 | `Disable` | Mapper desactivado, no borrado; copia en `backup/` y en `state.json` |
| 7 | `-Verify` | **Todo correcto.** `nexora-intelligence`: `nexora-semantic` y `nexora-reports`. Usuarios y `nexora-reports-dev`: solo `nexora-semantic` |
| 8 | `Disable` repetido | «nada que hacer» |
| 9 | `-Inspect` | Sin aviso |
| 10 | `Restore` | Configuración original de vuelta; `-Verify` vuelve a fallar igual |
| 11 | `Disable` + `-Revert` | `-Revert` restaura el mapper y borra solo lo marcado INT-3.7 |
| 12 | `-Inspect` | El realm queda como estaba |

Pruebas sin red (`pruebas-unitarias.log`): 108 de 108 correctas. Incluyen dos comprobaciones nuevas: el verificador sigue prohibiendo `nexora-reports` a los humanos, y la corrección nunca borra mappers. Ningún secreto aparece en las salidas.
