# Nexora Intelligence

Microservicio de Nexora AI que interpretará preguntas en lenguaje natural y las convertirá en solicitudes estructuradas para Nexora Semantic.

- Requisitos: Java 21 y Maven 3.9+.
- Compilar y probar: `mvn clean verify`
- Ejecutar: `mvn spring-boot:run` (puerto predeterminado `8100`; cambiarlo con la variable `INTELLIGENCE_PORT`).
- Salud: `GET /actuator/health`

## Motor de interpretación (INT-2)

El adaptador `OpenAiCompatibleQuestionInterpreter` implementa `QuestionInterpreter` sobre cualquier servidor con API compatible con OpenAI Chat Completions (Ollama, vLLM, llama.cpp, LM Studio). Solo se registra si se configura la URL base; sin ella el servicio arranca sin intérprete.

- Ollama local con `gpt-oss:20b`: perfil `ollama` (`--spring.profiles.active=ollama`), que apunta a `http://localhost:11434/v1`.
- Variables de entorno:
  - `NEXORA_INTELLIGENCE_INFERENCE_BASE_URL`: URL base de la API (activa el adaptador).
  - `NEXORA_INTELLIGENCE_INFERENCE_MODEL`: modelo (predeterminado `gpt-oss:20b`).
  - `NEXORA_INTELLIGENCE_INFERENCE_API_KEY`: token opcional; nunca se registra en logs.
- Resto de propiedades en `nexora.intelligence.inference.*` (`connect-timeout`, `read-timeout`, `max-retries` 0–1, `invalid-output-retries` 0–1, `structured-output`, `max-tokens`, `reasoning-effort`).
- Respuestas rechazadas (INT-3.7, V7): la salida del modelo se valida siempre de forma estricta y nunca se repara.
  - Si una respuesta **completa** (`finish_reason=stop`) no supera la validación, se pide **una sola vez más** (`invalid-output-retries`, predeterminado 1). Se reenvía la misma petición con una nota fija que nombra la regla incumplida, nunca la respuesta rechazada, y la nueva respuesta se valida igual. Si también es inválida: `INVALID_MODEL_OUTPUT` (HTTP 502 en la API).
  - Nunca se repite por errores HTTP, tiempos agotados, respuestas truncadas (`length`) o filtradas, respuestas demasiado grandes ni falta de contexto. Tampoco si la petición con la nota no cabe en el contexto.
  - El log registra `attempts`, la regla incumplida y, en un período rechazado, su forma (`kind` y nombres de los campos con valor), nunca valores, la pregunta ni la salida.
- Esquema del período: `period` es `null` o una de siete formas exactas, una por `kind`, cada una con valor solo en sus campos y `null` en los demás. Así un servidor que aplica el esquema no puede generar un `kind` con los campos de otro (comprobado con el conversor de esquemas a gramática de llama.cpp, el que usa Ollama: la gramática rechaza todas las combinaciones inválidas). Ollama aplica el esquema con `gpt-oss` en el endpoint de chat desde la 0.12.5, con informes de fallos intermitentes (issue ollama/ollama#11691); por eso la validación estricta y la repetición siguen siendo necesarias. Las formas añaden unos 900 tokens estimados: si con ellas y la posible nota la petición no cabe en `context-tokens`, se envía la forma compacta anterior (un objeto con todos los campos anulables) y el log lo indica con `periodSchema=compact`. La validación es idéntica en ambos casos. Con `context-tokens` 8192 y `max-tokens` 4096, un catálogo como el de la demo usa la forma compacta; con 12288 o más (y el mismo valor en `OLLAMA_CONTEXT_LENGTH`), la exacta.
- Contexto del modelo (INT-3.3b): cada pregunta envía las reglas, ejemplos ficticios, el catálogo semántico (solo alias y nombres lógicos) y el esquema JSON, y reserva `max-tokens` (4096) para la respuesta. Antes de enviar se estima el tamaño y, si no cabe en `context-tokens` con un 10 % de margen, la solicitud se rechaza (`CONTEXT_LIMIT_EXCEEDED`) sin llamar al modelo.
  - `NEXORA_INTELLIGENCE_INFERENCE_CONTEXT_TOKENS` (predeterminado 8192) **debe coincidir** con el contexto real del servidor. En Ollama: `setx OLLAMA_CONTEXT_LENGTH 16384` (reiniciar Ollama) y `NEXORA_INTELLIGENCE_INFERENCE_CONTEXT_TOKENS=16384`. Si Intelligence declara más contexto del que tiene Ollama, Ollama puede truncar el prompt sin avisar.
  - Con 8192 caben catálogos pequeños (unas 15–20 definiciones); para catálogos mayores, usar 16384 o más.
- Prueba opcional contra un servidor real: definir `NEXORA_REAL_INFERENCE_URL` (y opcionalmente `NEXORA_REAL_INFERENCE_MODEL`, `NEXORA_REAL_INFERENCE_CONTEXT_TOKENS` y `NEXORA_REAL_INFERENCE_REPEAT`) antes de `mvn verify`; sin esa variable se omite. Incluye una pregunta sin rango de tiempo repetida (5 veces por defecto) sin la repetición de respuestas rechazadas, que falla ante una sola desviación del modelo.

## Integración con Nexora Semantic (INT-3.4)

Puertos `SemanticCatalogClient` y `LogicalPlanClient` (adaptadores HTTP en `infrastructure.semantic`) y el servicio de aplicación `QuestionPlanningService` (catálogo → interpretación y reglas deterministas → plan lógico). Solo se registran si se configura `nexora.intelligence.semantic.base-url`; el servicio de planificación, además, requiere el motor de inferencia. **No hay endpoint HTTP de negocio.**

- Catálogo: `GET /api/v1/data-sources/{id}/definitions?kind=METRIC|DIMENSION&status=APPROVED` y `GET …/relationships?status=APPROVED`, páginas de 200, en cada solicitud (sin caché). Solo se conservan elementos `usable`; nunca se trunca (más de 200 por categoría ⇒ `CATALOG_TOO_LARGE`, restricción del MVP). Los tipos (`valueType`) vienen de Semantic, sin llamadas por columna.
- Planes: `POST /api/v1/data-sources/{id}/logical-plans`. Se verifica la respuesta 201 (`dataSourceId`, `fingerprint`, `formatVersion` = `nexora-logical-plan/1`); el documento del plan no se interpreta. Un 422 se devuelve como `Rejected` con origen SEMANTIC. Un POST enviado sin respuesta es `OUTCOME_UNKNOWN` y nunca se reintenta.
- Identidad de interpretación (`nexora.intelligence.semantic.identity.*`): client credentials contra el Keycloak interno; cada token se verifica (firma JWKS con algoritmos permitidos, `exp`/`nbf`, `iss`, `aud` = `nexora-semantic`, `principal_type` = `SERVICE`, rol `REPORTS_CLIENT`, `tenant` igual al del scope) antes de enviarse. El secreto solo se lee de `NEXORA_INTELLIGENCE_SEMANTIC_CLIENT_SECRET`. Una futura identidad de IA para enriquecer el catálogo debe tener su propio prefijo y credenciales.
- Zonas horarias (`nexora.intelligence.planning.*`): `default-zone` (UTC, valor técnico) y `source-zones.<dataSourceId>`; la zona propia de una dimensión siempre tiene prioridad.
- Ejemplo completo comentado en `src/main/resources/application.yml`.

## Integración con Nexora Reports (INT-3.5)

Puerto `ReportExecutionClient` (adaptador HTTP en `infrastructure.reports`) y `QuestionPlanningService.answer`: pregunta → plan → ejecución → resultado vinculado a las definiciones semánticas (`QuestionAnswer`). Solo se registra si se configura `nexora.intelligence.reports.base-url`. El punto de entrada HTTP se añadió en INT-3.6 (ver abajo).

- Ejecución: `POST /api/v1/data-sources/{id}/plans/{planId}/executions` con solo `{"maxRows": n}` (`max-rows`, por defecto 1000, máx. 10 000). Reports obtiene el plan de Semantic, lo revalida y lo ejecuta en solo lectura; Intelligence nunca envía SQL.
- Resultado: lectura estricta y sin conversiones (NUMERIC como `BigDecimal` exacto desde texto decimal, INTEGER como `BigInteger`, fechas y horas ISO-8601). Debe coincidir con el plan (id, fuente, huella), con las definiciones pedidas (columnas por clave y rol) y con sus tipos; si no, `CONTRACT_VIOLATION` y no se presenta nada. Los resultados truncados se marcan como tales.
- Rechazos de Reports sobre el plan (`plan_stale`, `plan_changed_during_execution`, `physical_schema_mismatch`, `plan_not_executable`) ⇒ `QuestionAnswer.NotExecuted` con `Rejected` de origen REPORTS. El resto ⇒ `ReportsUnavailableException` con motivo controlado. Una ejecución enviada nunca se repite automáticamente.
- Identidad de ejecución (`nexora.intelligence.reports.identity.*`): misma verificación JWKS que INT-3.4 (componentes compartidos en `infrastructure.serviceclient`), con audiencia `nexora-reports` y rol `REPORT_RUNNER`. Puede ser el mismo cliente OAuth2 que la interpretación si tiene ambas audiencias y roles. Secreto solo desde `NEXORA_INTELLIGENCE_REPORTS_CLIENT_SECRET`.

## API de preguntas (INT-3.6)

`POST /api/v1/questions` (adaptador de entrada en `infrastructure.web`) devuelve el resultado de `QuestionPlanningService.answer` como JSON. **Desactivada por defecto** (`nexora.intelligence.api.enabled=false`); al activarla exige Semantic y Reports configurados, o la aplicación no arranca.

- Petición (UTF-8, `application/json`, máx. `max-body-bytes`, por defecto 8 KiB): `{"question": "...", "language": "es"|"en" (opcional), "context": {"dataSource": "<alias>"}}`. Lectura estricta: campos desconocidos, duplicados o contenido sobrante ⇒ 400.
- El alias se resuelve contra `nexora.intelligence.api.data-sources.<alias>.tenant-id/data-source-id`. El tenant y la fuente **nunca** se toman de la petición. Alias desconocido ⇒ 400 `validation_failed`.
- Respuesta 200 con `status`: `REPORTED` (plan + `result` con columnas por clave de definición, filas, `rowCount`, `truncated`, avisos), `NEEDS_CLARIFICATION`, `UNSUPPORTED`, `REJECTED` o `NOT_EXECUTED` (`rejection` con origen y códigos de causa). Decimales exactos como texto decimal (sin notación científica, igual que Reports); enteros como número JSON; fechas ISO-8601.
- Errores: `application/problem+json` (RFC 7807) con `code` fijo y `detail` constante; nunca SQL, tokens, URLs internas ni mensajes de excepción. 403 `local_only`, 413, 415, 429 `too_many_questions` (`max-concurrent-questions`, por defecto 2) o `reports_busy`, 409 `data_source_archived`, 502/503/504 según el servicio que falle, 500 `internal_error`.
- Cada respuesta lleva `X-Request-Id` con el `correlationId` asignado por el servidor (no se acepta uno del cliente). Los registros incluyen ese id, el alias, el tipo de respuesta y la duración; nunca el texto de la pregunta.
- Seguridad del piloto: **sin autenticación de usuario final**; un filtro rechaza toda petición cuya dirección remota no sea loopback (`127.0.0.0/8`, `::1`), sin confiar en `X-Forwarded-For`. No debe exponerse fuera del equipo hasta que exista autenticación (decisión pendiente, Keycloak).

Ejemplo (PowerShell):

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8100/api/v1/questions -ContentType 'application/json; charset=utf-8' -Body '{"question":"¿Cuántos pozos hay por campo?","context":{"dataSource":"wellnode-demo"}}'
```

Integración local de los cuatro servicios (INT-3.7, en preparación, sin ejecutar): `validation/int37/README.md`.

Estado: INT-3.4 verificada en Windows por el usuario (`mvn clean verify`, pruebas automatizadas; sin E2E real con Keycloak). INT-3.5 e INT-3.6 implementadas y probadas en el entorno de Claude; pendientes de verificación en Windows. Sin E2E real.
