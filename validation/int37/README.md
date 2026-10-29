# INT-3.7 — Integración real local

**Estado (29 oct 2026): integración validada y completada en Windows, con DATOS SINTÉTICOS.** V3, V5, V6 y V7 pasan en el equipo del usuario: los cuatro servicios Java (Discovery, Semantic, Reports e Intelligence), PostgreSQL, Keycloak y Ollama (`gpt-oss:20b`) funcionan integrados de extremo a extremo. Evidencias, causa y corrección del fallo de V7, y limitaciones abiertas: **sección 12**.

**No es una preparación para producción**: es una integración validada en local con datos ficticios (sección 12.6).

Las secciones 1 a 11 describen la preparación y las pruebas aisladas, y se conservan como referencia del procedimiento.

Referencias:

- revisión técnica: `claude/int-3.7-revision-integracion.md` (proyecto Wellnode IA);
- catálogo y preguntas: `claude/demo-wellnode-especificacion.md`.

## 1. Decisiones aplicadas

- Tenant: se reutiliza `tenant-demo`.
- Un único cliente de servicio, `nexora-intelligence`, para Semantic y Reports.
- Datos: conjunto **sintético** e identificado como tal (base `nexora_demo_sintetico`, fuente `wellnode-sintetico`, alias `wellnode-sintetico`). No se copia ni se consulta nada de Wellnode.
- Sin cambios de código en Discovery, Semantic, Reports ni Intelligence. Solo se usan sus contratos y su configuración existentes.
- Preguntas:
  - P1: «¿Cuántos pozos hay por campo?»
  - P2: «¿Cuántos pozos hay por estatus del pozo?»
  - P3: «¿Cuántas actividades se registraron por día entre el 1 y el 7 de octubre de 2026?». Intervalo semiabierto [2026-10-01 00:00, 2026-10-08 00:00). Intelligence convierte el rango 1–7 (ambos días incluidos) en ese intervalo con `DateRange.inclusive`.

## 2. Archivos

| Archivo | Qué hace | ¿Modifica algo? |
|---|---|---|
| `Int37Common.psm1` | Funciones compartidas: secretos (sin mostrarlos), `.env`, HTTP, resumen de claims, comparación de filas | No |
| `Update-Int37Credentials.ps1` | `-Check` · `-Rotate` (AUT-1a, Keycloak) · `-RotatePostgres -Target Semantic\|Reports` (AUT-1b) · `-Recover` (cualquiera de las dos) · `-InitializeStore` · `-SetTenantDemoId` | `-Rotate` y `-SetTenantDemoId` editan `Nexora-Semantic/docker/.env`. `-RotatePostgres` cambia roles con `ALTER ROLE` y edita el `.env` de Semantic o de Reports (ignorados por Git). `-InitializeStore` crea el almacén local |
| `Int37Rotation.psm1` | Motores de rotación en caliente (Keycloak y PostgreSQL): diario, reversión, `-Recover` y verificadores SCRAM-SHA-256; independientes del HTTP y de Docker | — |
| `tests/Int37AdminSession.Tests.ps1` · `tests/Int37Rotation.Tests.ps1` · `tests/Int37PgRotation.Tests.ps1` | Pruebas sin red ni Docker: sesión de administración, rotación de Keycloak y rotación de PostgreSQL con fallos e interrupciones inyectados | No |
| `Invoke-Int37Keycloak.ps1` | `-Inspect` (V0) · `-Inventory` · `-Apply` · `-Verify` (V2) · `-SharedReportsAudience Disable\|Restore` · `-Revert` | `-Apply`, `-SharedReportsAudience` y `-Revert` modifican Keycloak |
| `Initialize-Int37SyntheticDb.ps1` | Crea o recarga la base sintética | Sí: base nueva en el contenedor `nexora-reports-postgres` |
| `datos/01-crear-sintetico.sql` | Estructura, 20 pozos y 33 registros ficticios, rol de solo lectura | (lo ejecuta el script anterior) |
| `datos/02-referencia.sql` | SQL de referencia de P1–P3, en una transacción `READ ONLY` | No |
| `datos/esperado.json` | Resultados esperados de P1–P3 | — |
| `Invoke-Int37Catalog.ps1` | Fuente en Semantic, Discovery, importación y las 7 definiciones aprobadas (V4) | Sí: datos de desarrollo en Semantic |
| `Start-Int37Service.ps1` | Arranca un servicio con su configuración; `-PrintConfig` solo la muestra | No (crea `application-int37.yml` sin secretos en la carpeta local) |
| `Test-Int37EndToEnd.ps1` | `-Reference` (V3) · `-Manual` (V5) · `-Api` (V6) · `-Repeat n` (V7) | Crea planes en Semantic y ejecuciones (auditadas) en Reports |

Todos los scripts que modifican algo admiten `-WhatIf`.

**Carpeta local de INT-3.7**: `%USERPROFILE%\.nexora\int37`, o la indicada en `NEXORA_INT37_HOME`. Está fuera de todos los repositorios y solo tiene acceso el usuario actual. Contiene:

- `secrets.env`, el almacén de secretos;
- `state.json`, con identificadores no secretos (tenant, fuente, definiciones);
- `application-int37.yml`, la configuración de Intelligence, que se genera sin secretos;
- `out\`, los informes.

## 3. Configuración preparada para Keycloak

Todo lo crea `Invoke-Int37Keycloak.ps1 -Apply` por la API de administración. Es idempotente y solo añade objetos. Cada objeto queda marcado: atributo `nexora.created-by=INT-3.7` o correo `@nexora-int37.invalid`.

| Objeto | Configuración |
|---|---|
| Rol de realm `REPORT_RUNNER` | Se crea marcado. Si ya existe (por ejemplo, de la validación R1), se reutiliza sin marcar y `-Revert` no lo borra |
| *Client scope* `nexora-reports` | Mapper de audiencia `included.custom.audience = nexora-reports`, solo en el token de acceso |
| Cliente `nexora-intelligence` | Confidencial (`client-secret`), solo *client credentials* (sin flujo estándar, implícito ni *password grant*) y `principal_type = SERVICE` |
| Scopes del cliente | Por defecto, **solo** `nexora-semantic` y `nexora-reports` (más `basic` y `service_account`, que únicamente añaden `sub` y `client_id`). Ningún scope opcional |
| Cuenta de servicio de `nexora-intelligence` | Exactamente `REPORTS_CLIENT` + `REPORT_RUNNER`. Se quitan los roles por defecto del realm. Grupo `tenant-demo` |
| Usuario `demo.editor` | Rol `EDITOR`, grupo `tenant-demo`, contraseña propia del almacén |
| Usuario `demo.approver` | Rol `APPROVER`, grupo `tenant-demo`, contraseña propia, distinta de la del editor (Semantic exige que aprueben dos personas distintas) |
| Cuenta de servicio de Reports | Sin cambios: `nexora-reports-dev` ya tiene `REPORTS_CLIENT` y `tenant-demo`, y es la que usa Reports para leer los planes |

Claims que comprueba `-Verify` (V2). Solo se imprime un resumen, nunca el token:

| Principal | `iss` | `principal_type` | `tenant` | `roles` | `aud` |
|---|---|---|---|---|---|
| `nexora-intelligence` | `http://localhost:8180/realms/nexora` | SERVICE | UUID de demo | `REPORTS_CLIENT`, `REPORT_RUNNER` y ningún otro rol Nexora | `nexora-semantic` y `nexora-reports` |
| `demo.editor` | ídem | HUMAN | ídem | `EDITOR` solamente | `nexora-semantic`, sin `nexora-reports` |
| `demo.approver` | ídem | HUMAN | ídem | `APPROVER` solamente | ídem |
| `nexora-reports-dev` | ídem | SERVICE | ídem | `REPORTS_CLIENT`, sin `REPORT_RUNNER` | `nexora-semantic` |

Además, `-Verify` comprueba que `nexora-intelligence` rechaza el *password grant*. Si Semantic y Reports están arrancados, comprueba también que aceptan el token: Semantic responde 200, y Reports no responde 401/403 ante una fuente inexistente.

**Discrepancia del UUID del tenant.** `-Inspect` compara tres valores, que deben ser iguales y distintos del UUID todo ceros:

1. el atributo `tenant` del grupo `tenant-demo` en Keycloak;
2. `KC_TENANT_DEMO_ID` en `Nexora-Semantic/docker/.env`;
3. el tenant con clave `demo` registrado en Semantic.

Semantic es la referencia, porque sus fuentes y definiciones ya pertenecen a ese UUID. Si no coinciden: `Update-Int37Credentials.ps1 -SetTenantDemoId <UUID de Semantic>` y después recrear Keycloak. Mientras sean incoherentes, `-Apply` no se ejecuta.

**Conservar y restaurar.** Keycloak corre en `start-dev --import-realm` sin volumen. Sus datos viven en la base H2 interna del contenedor.

- `docker compose ... stop`/`start`/`restart keycloak`, reiniciar Docker Desktop o reiniciar Windows **conservan** todo.
- `up -d --force-recreate`, `down`, borrar el contenedor o «Reset to factory defaults» de Docker Desktop lo **borran**. Al volver a arrancar solo se reimporta `nexora-realm.json`.
- `--import-realm` no reimporta un realm que ya existe. Por eso un cambio en el `.env` **no** llega a Keycloak al reiniciar, y por eso la rotación se aplica en caliente (sección 4).

**Recrear Keycloak no forma parte de AUT-1 ni del uso normal.** Queda solo como recuperación ante desastres, porque:

- pierde todo lo creado en ejecución (`REPORT_RUNNER`, restos de R1, la configuración de INT-3.7, cambios manuales);
- pierde los **`sub` de todos los usuarios**, con lo que la autoría registrada en Semantic queda sin identidad (AD-01.3);
- cambia las claves de firma.

Si hubiera que recrear:

1. Antes, si se puede: `.\Invoke-Int37Keycloak.ps1 -Inventory`.
2. Después: `-Inspect`, `-Apply` y `-Verify`.
3. Reconstruir a mano lo que el inventario marque como «solo en ejecución».
4. Reiniciar Semantic, Reports e Intelligence. Keycloak genera claves de firma nuevas, y un servicio con un token en caché falla en la primera llamada: en AUT-0, Reports respondió una vez `502 service_identity_invalid`.

El `.env` y el almacén conservan los secretos. Hacer la configuración permanente en `nexora-realm.json` exigiría modificar Semantic, y queda pospuesto. **No ejecutes** `Invoke-R1Validation.ps1 -RevertKeycloakSetup`, porque borra `REPORT_RUNNER` sin comprobar quién lo usa.

**Revertir.** `Invoke-Int37Keycloak.ps1 -Revert` elimina solo los objetos marcados INT-3.7. `REPORT_RUNNER` se elimina únicamente si lo creó INT-3.7 y nadie más lo tiene. Si `-SharedReportsAudience Disable` desactivó el mapper compartido, `-Revert` lo restaura primero.

**Audiencia `nexora-reports` en el scope compartido `nexora-semantic` (fallo de `-Verify` del 29 de octubre).**

- **Síntoma:** `-Verify` falla en `demo.editor` y `demo.approver` con «aud no contiene nexora-reports». Sus roles, el tenant y `aud=nexora-semantic` son correctos.
- **Causa:** en tu realm, el *client scope* compartido `nexora-semantic` tiene un mapper `nexora-reports-audience` (`oidc-audience-mapper`, `included.custom.audience=nexora-reports`, en el token de acceso).
  - No está en `nexora-realm.json`, ni lo crean `-Apply` ni R1. El inventario de las 04:01 UTC, anterior a `-Apply`, ya lo contenía: se añadió a mano en ejecución.
  - Como `nexora-semantic` es scope por defecto de `nexora-semantic-dev` y de `nexora-reports-dev`, **todo** token con ese scope recibe `aud=nexora-reports`, también los de los usuarios humanos.
  - El verificador exige, por mínimo privilegio, que solo `nexora-intelligence` tenga esa audiencia. `nexora-intelligence` la recibe de su propio scope `nexora-reports` (INT-3.7), no del compartido.
- **Corrección:** `.\Invoke-Int37Keycloak.ps1 -SharedReportsAudience Disable -WhatIf`, después sin `-WhatIf`, y `-Verify`.
  - Comprueba antes que `nexora-intelligence` ya recibe `nexora-reports` de su scope INT-3.7. Si no, se niega.
  - Muestra qué clientes y qué usuarios humanos con `REPORT_RUNNER` dejan de recibir la audiencia.
  - **Desactiva** el mapper: pone a `false` los claims del token de acceso, de introspección y ligero, **sin borrarlo**. Antes guarda su configuración original en `state.json` y en `backup/`, sin secretos.
  - Es idempotente. `-SharedReportsAudience Restore` (o `-Revert`) devuelve la configuración original.
- **Efecto:**
  - `nexora-intelligence` conserva `nexora-semantic` y `nexora-reports`.
  - `nexora-reports-dev` queda con `nexora-semantic`, que es lo único que necesita para leer planes de Semantic.
  - Los usuarios humanos quedan con `nexora-semantic`.
  - Los tokens ya emitidos conservan la audiencia hasta que caducan.
  - **Consecuencia:** un usuario humano con `REPORT_RUNNER` (en tu realm, `steward.demo`) deja de poder llamar a Reports directamente con un token de `nexora-semantic-dev`. Para llamar a Reports desde Postman, usa un token de `nexora-intelligence` (*client credentials*), que es el camino de la demo.
  - Al recrear Keycloak el mapper desaparecería de todos modos, porque no está en el fichero del realm.

## 4. Rotación de credenciales (AUT-1, en caliente)

AUT-1 se divide en dos partes independientes, cada una con su autorización:

| Parte | Qué rota | Comando | Sección |
|---|---|---|---|
| **AUT-1a** | Keycloak: `KC_DEV_USER_PASSWORD`, `KC_REPORTS_CLIENT_SECRET`, `KC_BOOTSTRAP_ADMIN_PASSWORD` | `-Rotate` | 4.1 |
| **AUT-1b** | PostgreSQL: las tres credenciales de Semantic y las tres de Reports | `-RotatePostgres -Target Semantic` y `-RotatePostgres -Target Reports` | 4.2 |

`-Check` solo queda sin problemas cuando se han hecho AUT-1a, las dos ejecuciones de AUT-1b y `-InitializeStore`. Solo puede haber **una rotación pendiente a la vez**: mientras un diario esté pendiente, ninguna rotación arranca y `-Recover` atiende primero esa.

**No ejecutes `docker compose … up -d` después de rotar.** Docker Compose recrea los servicios cuya configuración cambió con el `.env`. Keycloak no tiene volumen, así que perdería sus datos (sección 3). Usa `docker compose … start`, `stop` o `restart`, que no recrean.

### 4.1 AUT-1a — Keycloak (sin recrear Keycloak)

El 29 de octubre quedaron expuestos en la salida de una herramienta de Claude los valores de `KC_DEV_USER_PASSWORD` (los 5 usuarios de desarrollo del realm, incluido `platform.admin`) y de `KC_REPORTS_CLIENT_SECRET` (`nexora-reports-dev`). Además, tenían el mismo valor y eran cortos. No se han copiado a ningún sitio.

**Qué se rota:**

| Credencial | Dónde | Cuándo |
|---|---|---|
| `KC_DEV_USER_PASSWORD` | Los usuarios del realm cuya contraseña es `${KC_DEV_USER_PASSWORD}`. Se leen de `nexora-realm.json`: `platform.admin`, `steward.demo`, `viewer.demo`, `auditor.demo`, `steward.other` | Siempre |
| `KC_REPORTS_CLIENT_SECRET` | El cliente cuyo secreto es `${KC_REPORTS_CLIENT_SECRET}` (`nexora-reports-dev`) | Siempre |
| `KC_BOOTSTRAP_ADMIN_PASSWORD` | El usuario `admin` de `master` | Si es corta (menos de 24) o se repite, o con `-IncludeAdmin`. **Siempre la última** |

Los valores nuevos tienen 32 caracteres del generador criptográfico del sistema y nunca se muestran.

**Procedimiento AUT-1a** (requiere autorización):

1. `.\Update-Int37Credentials.ps1 -Check`: nombres débiles o compartidos, variables de entorno que anulan secretos, diario pendiente.
2. `.\Invoke-Int37Keycloak.ps1 -Inventory`: inventario **sin secretos** con los `sub` actuales y los objetos que solo existen en ejecución. **No es una copia restaurable**: no tiene contraseñas, secretos ni claves de firma, y Keycloak asigna ids nuevos al importar. Sirve de referencia para comparar y para reconstruir a mano.
3. `.\Update-Int37Credentials.ps1 -Rotate -WhatIf`: muestra el plan, solo con nombres.
4. `.\Update-Int37Credentials.ps1 -Rotate`. Hace, en orden:
   1. **Comprueba** que no hay un diario pendiente y que **cada credencial actual del `.env` autentica** en Keycloak: el administrador, los 5 usuarios y el cliente. Si alguna no lo hace, no toca nada.
   2. Escribe el **diario** (`%USERPROFILE%\.nexora\int37\rotation-journal.env`, accesible solo para tu usuario) con los valores anteriores y los nuevos.
   3. Cambia las contraseñas de los usuarios y verifica cada una pidiendo un token.
   4. Cambia el secreto del cliente y lo verifica.
   5. Cambia la contraseña del administrador de `master`, **la última**: la cambia con una sesión de administración recién abierta, verifica el nuevo acceso y abre una sesión nueva con él.
   6. Sustituye el `.env` en **una sola escritura atómica** (fichero temporal y renombrado), lo vuelve a leer y lo compara.
   7. Reescribe el diario **sin secretos** (estado `COMPLETED`).
5. `.\Update-Int37Credentials.ps1 -InitializeStore` crea el almacén con cuatro secretos nuevos y distintos:
   - `NEXORA_INTELLIGENCE_CLIENT_SECRET`;
   - `NEXORA_DEMO_EDITOR_PASSWORD`;
   - `NEXORA_DEMO_APPROVER_PASSWORD`;
   - `NEXORA_SINTETICO_READER_PASSWORD`.
6. `-Inspect` coherente. `-Check` ya no señala las credenciales de Keycloak; las de PostgreSQL desaparecen con AUT-1b (sección 4.2).
7. Si Reports está arrancado, reinícialo: toma `KC_REPORTS_CLIENT_SECRET` al arrancar. Semantic no necesita reinicio, porque las claves de firma no cambian. Actualiza Postman u otras herramientas con la nueva contraseña de desarrollo, leyéndola del `.env`.

**Qué se conserva y qué cambia:**

- **Se conserva:** todos los usuarios y sus `sub`, `REPORT_RUNNER` y demás objetos creados en ejecución, roles, clientes, grupos, claves de firma, y los datos de Semantic y Reports.
- **Cambia solo:** 2 o 3 credenciales en Keycloak y en el `.env`, y el almacén nuevo.

**Recuperación:**

| Situación | Qué ocurre o qué hacer |
|---|---|
| Falla cualquier paso (Keycloak, una verificación o el `.env`) | **Reversión automática**: primero el administrador, después el cliente, los usuarios y el `.env`. Se comprueba que los valores anteriores vuelven a autenticar. El diario queda sin secretos (`ROLLED_BACK`) |
| La reversión automática también falla (por ejemplo, Keycloak deja de responder) | El diario queda `ROLLBACK_FAILED` con lo necesario. Cuando Keycloak responda: `.\Update-Int37Credentials.ps1 -Recover` |
| **El proceso muere** (corte de luz, ventana cerrada, `Ctrl+C`) en cualquier momento, incluso antes de la reversión automática | El diario queda pendiente; `-Check` lo señala y `-Apply` se niega. Ejecuta `.\Update-Int37Credentials.ps1 -Recover`: **decide el `.env`**. Si ya contiene todos los valores nuevos, completa Keycloak con ellos; si no, deja Keycloak y el `.env` con los anteriores. Comprueba cada credencial con un inicio de sesión real y se puede repetir sin riesgo |
| Queda un `.env.int37tmp` junto al `.env` | Restos de una escritura interrumpida, sin efecto. El `.env` es siempre el anterior o el nuevo completo. Revísalo y bórralo a mano |
| `-Recover` responde «No administrative access» | Nadie conoce la contraseña del administrador (por ejemplo, porque se cambió fuera del proceso). Opciones, en orden: (1) entrar en la consola de Keycloak con alguno de los dos valores `OLD_`/`NEW_KC_BOOTSTRAP_ADMIN_PASSWORD` del diario; (2) crear un administrador temporal con `kc.sh bootstrap-admin user` dentro del contenedor (**hay que comprobar en Windows** si funciona con el servidor arrancado en `start-dev`); (3) último recurso, recrear Keycloak (ver «Conservar y restaurar») y reconstruir a partir del inventario |

Mientras un diario esté pendiente, contiene los valores anteriores y los nuevos en claro, protegido solo por los permisos de tu usuario, igual que el almacén. Al terminar, de cualquier forma, se reescribe sin secretos.

**Contraseñas compartidas entre usuarios** (propuesta, **pendiente de aprobación**): la rotación mantiene una sola contraseña para los 5 usuarios de desarrollo.

Quién depende hoy de esa contraseña:

- el fichero del realm, `${KC_DEV_USER_PASSWORD}` en los 5 usuarios, y el `docker-compose.yml` de Semantic;
- `Invoke-R1Validation.ps1`, para `steward.demo`, `steward.other` y `viewer.demo`;
- los scripts de INT-3.7: `platform.admin` en la comprobación del tenant y `steward.demo` en el catálogo.

Opciones:

- **A (provisional, sin tocar otros repositorios):** contraseñas propias solo para `platform.admin` y `auditor.demo`, que R1 no usa, con variables nuevas en el `.env` y lectura con valor por defecto en los scripts de INT-3.7. Pega: si se recrea Keycloak, el fichero del realm vuelve a ponerles la contraseña común.
- **B (completa, recomendada después de la demo):**
  - variables por usuario (`KC_PWD_PLATFORM_ADMIN`, `KC_PWD_STEWARD_DEMO`…) en `nexora-realm.json`, en el `docker-compose.yml` y en el `.env.example` de Semantic;
  - lectura por usuario, con valor por defecto, en `Invoke-R1Validation.ps1` (Reports) y en los scripts de INT-3.7;
  - el motor de rotación, ampliado a una clave por usuario.

  Requiere autorización para cambiar ficheros de configuración y validación de Semantic y Reports, pero no su código de servicio.

### 4.2 AUT-1b — PostgreSQL (sin recrear contenedores)

Cambiar el `.env` no cambia nada en PostgreSQL: las contraseñas de los roles viven en la base (volúmenes `semantic-postgres` y `reports-pgdata`), y `POSTGRES_PASSWORD` y los scripts de `init` solo se usan al crear el volumen. Por eso la rotación se hace con `ALTER ROLE` sobre los contenedores en marcha.

**Qué se rota:**

| Destino | Clave del `.env` | Rol (base de verificación) | Quién lo usa |
|---|---|---|---|
| Semantic · `nexora-semantic-dev-postgres-1` | `SEMANTIC_DB_APP_PASSWORD` | `nexora_semantic_app` (`nexora_semantic`) | El pool de conexiones de Semantic |
| | `SEMANTIC_DB_OWNER_PASSWORD` | `nexora_semantic_owner` (`nexora_semantic`) | Flyway, solo al arrancar Semantic |
| | `POSTGRES_PASSWORD` | `postgres`, superusuario, **el último** | Administración. Ningún servicio |
| Reports · `nexora-reports-postgres` | `REPORTS_DB_APP_PASSWORD` | `nexora_reports_app` (`nexora_reports`) | El pool de conexiones de Reports |
| | `SYNTHETIC_READER_PASSWORD` | `nexora_reader` (`synthetic_erp`) | Solo la validación R1 |
| | `REPORTS_DB_OWNER_PASSWORD` | `nexora_reports_owner` (o `REPORTS_DB_OWNER_USER`), superusuario, **el último** | Flyway de Reports y administración |

`nexora_sintetico_reader` no entra aquí. Lo crea AUT-3 con su propio secreto del almacén.

**Procedimiento AUT-1b** (requiere autorización; una ejecución por destino):

1. `.\Update-Int37Credentials.ps1 -Check`.
2. `.\Update-Int37Credentials.ps1 -RotatePostgres -Target Semantic -WhatIf`. Muestra el plan, solo con nombres.
3. `.\Update-Int37Credentials.ps1 -RotatePostgres -Target Semantic`. Hace, en orden:
   1. **Comprobaciones previas.** Si alguna falla, no toca nada:
      - no hay ningún diario pendiente;
      - el contenedor está arrancado;
      - **vía de recuperación**: el superusuario entra sin contraseña por el *socket* local del contenedor (`docker exec … psql`, sin `-h`). Se comprueba con `current_setting('is_superuser')` y con `pg_hba_file_rules`, que debe tener una regla `local` con `trust` o `peer`. Es lo que permite recuperar aunque nadie conozca la contraseña del superusuario;
      - **vía de verificación con sentido**: cada rol se prueba por TCP contra la IP del propio contenedor (no contra `127.0.0.1`, que en la imagen oficial de PostgreSQL suele quedar en `trust`) con una contraseña falsa. Si alguna entra, la verificación no valdría nada y la rotación se niega;
      - cada valor actual del `.env` autentica por TCP.
   2. Escribe el diario (`%USERPROFILE%\.nexora\int37\rotation-journal.env`, solo tu usuario) con los valores anteriores y los nuevos.
   3. Cambia los roles **uno a uno**: aplicación, propietario y, el último, el superusuario. Después de cada `ALTER ROLE` verifica el valor nuevo con un inicio de sesión TCP real.
   4. Sustituye el `.env` en **una sola escritura atómica**, lo vuelve a leer y lo compara.
   5. Reescribe el diario **sin secretos** (estado `COMPLETED`).
4. **Reinicia Semantic enseguida** (Ctrl+C en su terminal y `.\Start-Int37Service.ps1 -Service Semantic`). Las conexiones ya abiertas del pool siguen funcionando, pero cada conexión nueva necesita la contraseña nueva, y Flyway usa la del propietario al arrancar.
5. Repite los pasos 2 a 4 con `-Target Reports`, y reinicia Reports si está arrancado. Actualiza R1, Postman, DBeaver u otras herramientas leyendo los valores del `.env`.

**Cómo viajan las contraseñas.** El script calcula en tu equipo el verificador SCRAM-SHA-256 (PBKDF2-HMAC-SHA256, 4096 iteraciones, sal aleatoria de 16 bytes) y envía `ALTER ROLE "rol" PASSWORD 'SCRAM-SHA-256$…'` por la entrada estándar de `psql`. La contraseña en claro **nunca llega al servidor**. Por eso no puede aparecer en `pg_stat_activity`, en el registro de sentencias ni en el historial de `psql`. Las verificaciones pasan la contraseña en `PGPASSWORD`, que viaja como variable de entorno de `docker exec -e PGPASSWORD`, nunca en la línea de comandos. Si el servidor registrara las sentencias DDL (`log_statement`), el registro tendría el verificador: no es la contraseña, y con 32 caracteres aleatorios no es atacable por fuerza bruta.

**Lo que no cambia:** datos, esquemas, permisos, volúmenes y contenedores. `docker inspect` seguirá mostrando los valores **anteriores** en las variables de entorno con que se crearon los contenedores. Ya no autentican. No recrees los contenedores para cambiarlo.

#### Recuperar cada base si el proceso se interrumpe entre el cambio de una contraseña y la escritura del `.env`

En ese intervalo la base y el `.env` no coinciden. Por ejemplo, `nexora_semantic_app` ya tiene la contraseña nueva y el `.env` aún tiene la anterior. El diario queda pendiente (`PREPARED`, `APPLYING` o `APPLIED`) con los valores anteriores y los nuevos, protegido por los permisos de tu usuario.

Efectos mientras dure la desincronización:

| Base | Efecto |
|---|---|
| Semantic | Las conexiones abiertas del pool siguen funcionando. Las nuevas de `nexora_semantic_app` fallan si ya cambió ese rol. **Semantic no arranca** si ya cambió `nexora_semantic_owner` (falla Flyway). `postgres` deja de entrar por TCP con el `.env`, pero sigue entrando por el *socket* local |
| Reports | Igual con `nexora_reports_app` (pool) y `nexora_reports_owner` (Flyway). R1 falla si ya cambió `nexora_reader` |

Qué hacer, en este orden:

1. **No** arranques ni reinicies el servicio afectado, **no** edites el `.env` a mano, **no** recrees el contenedor y **no** borres el diario.
2. `.\Update-Int37Credentials.ps1 -Check`. Señala «a rotation did not finish (pending journal)».
3. `.\Update-Int37Credentials.ps1 -Recover`. Lee el diario, que indica el destino y el contenedor, y **decide por el `.env`**:
   - si el `.env` ya tiene todos los valores nuevos (el proceso murió después de escribirlo), deja los roles con los nuevos;
   - si no, devuelve **todos** los roles del destino a los valores anteriores.

   Entra por el *socket* local del contenedor como superusuario, sin contraseña: en Semantic, `postgres` en `nexora-semantic-dev-postgres-1`; en Reports, `nexora_reports_owner` en `nexora-reports-postgres`. Así funciona aunque la contraseña del superusuario ya se hubiera cambiado. Solo cambia los roles que no autentican con el valor elegido, verifica cada uno por TCP, escribe el `.env` si hace falta, marca el diario `RECOVERED` y le quita los secretos. Se puede repetir sin riesgo: la segunda vez responde «No hay ninguna rotación pendiente».
4. Reinicia el servicio si estaba arrancado.
5. Cuando convenga, vuelve a ejecutar `-RotatePostgres` sobre ese destino.

Otras situaciones:

| Situación | Qué ocurre o qué hacer |
|---|---|
| Falla un paso detectado por el propio script (`ALTER ROLE`, una verificación o el `.env`) | **Reversión automática** en orden inverso. Solo cambia los roles que ya no aceptan el valor anterior, restaura el `.env` si llegó a escribirse y verifica. Diario `ROLLED_BACK`, sin secretos |
| La reversión automática también falla (por ejemplo, el contenedor se detuvo) | Diario `ROLLBACK_FAILED`. Arranca el contenedor (`docker compose … start` o `docker start <contenedor>`, nunca `up -d`) y ejecuta `-Recover` |
| Queda un `.env.int37tmp` junto al `.env` | Restos de una escritura interrumpida, sin efecto. El `.env` es siempre el anterior o el nuevo completo. Revísalo y bórralo a mano |
| `-Recover` responde «no passwordless superuser access» | El contenedor ya no permite entrar por el *socket* sin contraseña. No toca nada y el diario sigue pendiente. Ver «Alineación manual» |
| El diario se perdió estando pendiente | Los valores nuevos solo estaban en el diario. Ver «Alineación manual», poniendo los valores que tiene el `.env` |

**Alineación manual (no automatizada; solo con tu autorización explícita para ese caso).** Para un rol cuya contraseña en la base no coincide con el `.env`:

1. `docker exec -it <contenedor> psql -U <superusuario> -d postgres`, por el *socket* local y sin contraseña.
2. `\password <rol>` y teclea el valor del `.env`, o el `OLD_…`/`NEW_…` del diario si se decide volver a uno de ellos. `psql` calcula el verificador en tu equipo, y la contraseña no queda en el historial ni en los registros.
3. Comprueba con `-Check` y con un inicio de sesión del servicio.

Si tampoco hay acceso por el *socket*, solo queda entrar por TCP con alguno de los valores del diario. Cambiar `pg_hba.conf` queda fuera de este procedimiento y se consultaría antes.

### 4.3 Reglas comunes

Reglas que cumplen los scripts:

- ningún secreto en ficheros versionables, argumentos de línea de comandos, consola ni informes;
- los secretos solo existen en el `.env` ignorado, en el almacén local o en el entorno del proceso del servicio, que se limpia al terminar;
- de los tokens solo se imprime un resumen de claims, sin `sub`.

## 5. Conjunto sintético

- Base `nexora_demo_sintetico` y esquema `sintetico`, en el PostgreSQL de desarrollo de Reports (`127.0.0.1:5434`, contenedor `nexora-reports-postgres`). Base, esquema y tablas llevan comentarios «DATOS SINTÉTICOS (FICTICIOS)». Los valores son inequívocos: `POZO FICTICIO 001`, `CAMPO FICTICIO ALFA`, `OPERADORA FICTICIA S.A.`, `ACTIVIDAD SINTETICA 001`.
- Estructura mínima de las 7 definiciones:
  - `wm_well(dbid PK, uid, name, field, statuswell, state, operator)`;
  - `bkw_electronic_logs(id PK, uid_well, start_date timestamp, end_date timestamp, description)`.

  Los tipos son los que suponen las entidades JPA. Los tipos reales de Wellnode siguen pendientes de verificación.
- Datos fijos y reproducibles. Cada carga vacía las dos tablas y vuelve a insertar las mismas filas.
  - 20 pozos.
  - 33 registros. Incluyen los bordes de P3: el 30 de septiembre a las 23:59:59 y el 8 de octubre a las 00:00 quedan fuera; el 1 de octubre a las 00:00 y el 7 de octubre a las 23:59:59 quedan dentro. El 3 de octubre no tiene registros, y hay un registro sin fecha.
- Rol `nexora_sintetico_reader`: `LOGIN`, no superusuario, `default_transaction_read_only = on`, `CONNECT` solo a esa base, `USAGE` del esquema y `SELECT` sobre las dos tablas. Reports rechaza cualquier rol con privilegios de escritura.
- Resultados esperados (`datos/esperado.json`), con orden ascendente y NULL al final:

| P1 campo → pozos | P2 estatus → pozos | P3 día → actividades |
|---|---|---|
| CAMPO FICTICIO ALFA 7 | ABANDONADO 2 | 2026-10-01 3 |
| CAMPO FICTICIO BETA 6 | ACTIVO 8 | 2026-10-02 5 |
| CAMPO FICTICIO GAMMA 5 | CERRADO 4 | 2026-10-04 4 |
| (sin campo) 2 | EN PERFORACION 5 | 2026-10-05 6 |
| | (sin estatus) 1 | 2026-10-06 2 |
| | | 2026-10-07 7 |

## 6. Servicios, puertos y variables

| Orden | Servicio | Puerto | Cómo se arranca | Depende de |
|---|---|---|---|---|
| 1 | PostgreSQL de Semantic + Keycloak (Docker) | 5433, 8180 | `docker compose -f Nexora-Semantic/docker/docker-compose.yml --env-file Nexora-Semantic/docker/.env up -d` | — |
| 2 | PostgreSQL de Reports (Docker): `nexora_reports`, `synthetic_erp`, `nexora_demo_sintetico` | 5434 | `docker compose -f Nexora-Reports/docker/docker-compose.yml --env-file Nexora-Reports/docker/.env up -d` | — |
| 3 | Semantic | 8080 | `.\Start-Int37Service.ps1 -Service Semantic` (terminal 1) | 1 |
| 4 | Discovery | 8070 | `.\Start-Int37Service.ps1 -Service Discovery` (terminal 2) | — |
| 5 | Reports | 8090 | `.\Start-Int37Service.ps1 -Service Reports` (terminal 3) | 1, 2, 3 y catálogo (`state.json`) |
| 6 | Ollama | 11434 | `ollama serve` con `gpt-oss:20b` y `OLLAMA_CONTEXT_LENGTH=12288` (con 8192 funciona, pero Intelligence envía el esquema compacto del período; sección 12.3). `NEXORA_INTELLIGENCE_INFERENCE_CONTEXT_TOKENS` con el mismo valor | — |
| 7 | Intelligence | 8100 | `.\Start-Int37Service.ps1 -Service Intelligence` (terminal 4) | 1, 3, 5, 6 y `state.json` |

Antes, compila los jars con `mvn -DskipTests package` en cada uno de los cuatro repositorios. `-PrintConfig` muestra la configuración sin secretos y no arranca nada.

Variables que establece `Start-Int37Service.ps1`. «secreto» significa que se toma del `.env` o del almacén y solo existe en el proceso del servicio.

- **Semantic:**
  - `SPRING_PROFILES_ACTIVE=dev` (emisor `http://localhost:8180/realms/nexora`, audiencia `nexora-semantic`);
  - `SEMANTIC_DB_APP_PASSWORD`, `SEMANTIC_DB_OWNER_PASSWORD` (secreto).
- **Discovery:** solo argumentos: `--server.port=8070 --nexora.discovery.api.targets.allowed-networks=127.0.0.1/32 --nexora.discovery.api.targets.allowed-ports=5434`.
- **Reports:**
  - `REPORTS_PORT=8090`, `REPORTS_DB_URL`, `REPORTS_DB_USER`;
  - `REPORTS_DB_PASSWORD`, `REPORTS_DB_OWNER_PASSWORD` (secreto);
  - `REPORTS_OIDC_ISSUER_URI`, `REPORTS_SEMANTIC_BASE_URL`, `REPORTS_SEMANTIC_TOKEN_URI`;
  - `NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_0_{TENANTID,CLIENTID=nexora-reports-dev}`, y `…_CLIENTSECRET` (secreto);
  - `NEXORA_REPORTS_ENTERPRISE_ALLOWEDHOSTS_0=127.0.0.1`, `…_ALLOWEDPORTS_0=5434`;
  - `NEXORA_REPORTS_ENTERPRISE_SOURCES_0_{TENANTID,DATASOURCEID,HOST,PORT,DATABASE=nexora_demo_sintetico,USERNAME=nexora_sintetico_reader,SSLMODE=disable}`, y `…_PASSWORD` (secreto).
- **Intelligence:**
  - `SPRING_PROFILES_ACTIVE=ollama`;
  - `NEXORA_INTELLIGENCE_SEMANTIC_CLIENT_SECRET` y `NEXORA_INTELLIGENCE_REPORTS_CLIENT_SECRET` (secreto: el mismo, porque hay un solo cliente);
  - `--spring.config.additional-location=file:<carpeta local>/application-int37.yml`. Ese fichero contiene `semantic.identity.*` y `reports.identity.*` (tenant, emisor, JWKS, audiencias, `client-id: nexora-intelligence`, secretos como `${…}`), `planning.source-zones.<dataSourceId>=America/Mexico_City` y `api.enabled=true` con el alias `wellnode-sintetico` → (tenant, fuente).

Flujo, con las responsabilidades de siempre:

1. Discovery lee los metadatos y Semantic los importa.
2. Los usuarios humanos crean y aprueban las definiciones.
3. Intelligence interpreta la pregunta y pide el plan a Semantic.
4. Reports obtiene el plan de Semantic con su propia cuenta de servicio, lo revalida y lo ejecuta en solo lectura.
5. Intelligence vincula el resultado a las definiciones.

## 7. Secuencia exacta en Windows

PowerShell 7, desde `D:\desarrollo\java\Nexora AI\Nexora-Intelligence\validation\int37`. **[AUT]** marca los pasos que requieren autorización.

| Paso | Qué | Comando o acción | Criterio de éxito |
|---|---|---|---|
| 1 · V1 | Verificar INT-3.5 e INT-3.6 | En `Nexora-Intelligence`: `mvn clean verify` con las variables de Ollama | 517 pruebas, 0 fallos, 0 errores, 0 omitidas |
| 1b | Compilar | `mvn -DskipTests package` en los 4 repositorios | 4 jars |
| 2 · V0 | Estado real (solo lectura) | Docker de Semantic arrancado; terminal 1: `.\Start-Int37Service.ps1 -Service Semantic`; después `.\Invoke-Int37Keycloak.ps1 -Inspect` y `.\Update-Int37Credentials.ps1 -Check` | Informe del realm; tenant coherente o UUID de Semantic identificado |
| 3a **[AUT-1a]** | Inventario y rotación de Keycloak en caliente (**sin recrear Keycloak**) | `-Check`; `.\Invoke-Int37Keycloak.ps1 -Inventory`; `.\Update-Int37Credentials.ps1 -Rotate -WhatIf`, después `-Rotate`; reiniciar Reports si está arrancado (sección 4.1) | `-Inspect` coherente; el inventario conserva los `sub` |
| 3b **[AUT-1b]** | Rotación de PostgreSQL en caliente (**sin recrear contenedores**) | Docker de Semantic y de Reports arrancados; `.\Update-Int37Credentials.ps1 -RotatePostgres -Target Semantic -WhatIf`, después sin `-WhatIf`; reiniciar Semantic; lo mismo con `-Target Reports` y reiniciar Reports si está arrancado (sección 4.2) | Semantic arranca (Flyway y pool con los valores nuevos) |
| 3c **[AUT-1]** | Almacén local | `.\Update-Int37Credentials.ps1 -InitializeStore`; `-Check` | `-Check` sin problemas |
| 4 **[AUT-2]** | Configurar Keycloak | `.\Invoke-Int37Keycloak.ps1 -Apply -WhatIf`, después `-Apply`. Si `-Inspect` avisa del mapper compartido `nexora-reports` (sección 3): `-SharedReportsAudience Disable -WhatIf`, después sin `-WhatIf` | Sin errores |
| 5 · V2 | Claims | `.\Invoke-Int37Keycloak.ps1 -Verify` | Todo `[OK]`; Semantic acepta el token |
| 6 **[AUT-3]** | Base sintética | Docker de Reports arrancado; `.\Initialize-Int37SyntheticDb.ps1 -WhatIf`, después sin `-WhatIf` | Privilegios de escritura `f`; 20 pozos, 33 registros |
| 7 · V3 | SQL de referencia | `.\Test-Int37EndToEnd.ps1 -Reference` | `V3.P1–P3 PASS` |
| 8 | Discovery | Terminal 2: `.\Start-Int37Service.ps1 -Service Discovery` | Arranca en 8070 |
| 9 **[AUT-4]** · V4 | Fuente y catálogo | `.\Invoke-Int37Catalog.ps1 -WhatIf`, después sin `-WhatIf` | Solo 2 relaciones importadas; 7 definiciones APPROVED y usables; `campo` y `estatus_pozo` TEXT, `fecha_actividad` TIMESTAMP |
| 10 | Reports | Terminal 3: `.\Start-Int37Service.ps1 -Service Reports`; repetir `-Verify` | Reports autentica el token de Intelligence |
| 11 · V5 | P1–P3 sin Ollama | `.\Test-Int37EndToEnd.ps1 -Manual` | `V5.P1–P3 PASS`: mismas filas que `esperado.json` y que V3 |
| 12 | Ollama e Intelligence | `ollama serve`; terminal 4: `.\Start-Int37Service.ps1 -Service Intelligence` | Arranca en 8100 |
| 13 · V6 | P1–P3 por la API | `.\Test-Int37EndToEnd.ps1 -Api` | `V6.P1–P3 PASS`, `V6.N1–N2 PASS` |
| 14 · V7 | Estabilidad | `.\Test-Int37EndToEnd.ps1 -Repeat 5` (en el cierre se ejecutó con `-Repeat 3`) | Todas correctas por pregunta; anotar huellas y latencias |
| 15 | Ensayo de la demo | Las tres preguntas desde PowerShell (README de Intelligence, «API de preguntas») | Guardar los informes de `out\` como plan B |

Los informes de V3 a V7 quedan en `%USERPROFILE%\.nexora\int37\out\int37-<fecha>.md/.json`.

## 8. Autorizaciones necesarias antes de ejecutar

Estado al cierre: ver la sección 12.2. Los identificadores AUT-n de esta tabla son los de la preparación de INT-3.7, no los de etapas posteriores.

| Id | Acción | Efecto |
|---|---|---|
| AUT-0 (**completada**) | Que Claude pruebe los scripts en su entorno aislado (sección 10, `aut0/RESULTADOS.md`) | Ninguno en tus sistemas. No sustituye a Windows |
| AUT-1a | Inventario; rotación **en caliente** en Keycloak y en `Nexora-Semantic/docker/.env`; crear el almacén local | Contraseñas nuevas en Keycloak y en el `.env`. **No se recrea Keycloak**: se conservan usuarios, `sub` y objetos creados en ejecución |
| AUT-1b | `-RotatePostgres -Target Semantic` y `-Target Reports` | `ALTER ROLE` de 3 roles en cada contenedor y sus 3 claves en el `.env` de Semantic o de Reports. **No se recrean contenedores** ni se tocan datos. Requiere reiniciar Semantic y Reports |
| AUT-2 | `Invoke-Int37Keycloak.ps1 -Apply` (y `-Revert` si hiciera falta); `-SharedReportsAudience Disable` si el realm tiene el mapper compartido | Rol, *client scope*, cliente y dos usuarios en el realm `nexora`; el mapper compartido queda desactivado (no borrado) |
| AUT-3 | `Initialize-Int37SyntheticDb.ps1` | Base y rol nuevos en el PostgreSQL de desarrollo de Reports |
| AUT-4 | `Invoke-Int37Catalog.ps1` | Fuente `wellnode-sintetico`, importación y 7 definiciones en Semantic (desarrollo) |
| AUT-5 | `Test-Int37EndToEnd.ps1 -Manual/-Api/-Repeat` | Planes en Semantic y ejecuciones registradas en la auditoría de Reports |

## 9. Riesgos y dependencias pendientes

Riesgos de la preparación. Los que siguen abiertos tras el cierre están en la sección 12.5.

- ~~Primer uso real de los scripts~~: resuelto en el cierre (sección 12). Aparecieron dos incidencias reales y se corrigieron: el mapper compartido de audiencia (sección 3) y el período inválido de P2 (sección 12.3).
- ~~INT-3.5 e INT-3.6 sin verificar en Windows~~: `mvn clean verify` y V5–V7 pasan en Windows (sección 12.1).
- **Recreación de Keycloak.** Borra la configuración de INT-3.7, que se restaura con `-Apply`. El *revert* de R1 borra `REPORT_RUNNER`.
- **Modelo.** P3 depende de que `gpt-oss:20b` interprete «entre el 1 y el 7» como un rango de días completos. Cualquier desviación aparece en V6 y V7.
- **Datos reales.** Siguen bloqueados hasta que Wellnode autorice por escrito una compañía, pozos y un periodo, y se confirmen los tipos reales. Si `start_date` fuera `timestamptz`, la dimensión necesitaría `timeZone`. La demo con datos sintéticos debe presentarse como tal.
- **Recursos de la máquina:** 3 contenedores, 4 JVM y Ollama 20B. Prueba el arranque completo con antelación.
- **Persistencia permanente** de Keycloak (en `nexora-realm.json`) y separación de las contraseñas de los usuarios de desarrollo: pospuestas, porque exigen cambiar Semantic.

## 10. Pruebas aisladas (AUT-0)

Ejecutadas en el entorno aislado de Claude con la autorización AUT-0. **No son una verificación en Windows.** La evidencia está en `aut0/`:

- `RESULTADOS.md`: resumen;
- `steps.log`: salida completa de la ejecución limpia, sin secretos;
- `run-aut0.sh`, `keycloak_sim.py`, `llm_sim.py`: lo necesario para reproducirla.

Montaje:

- Semantic, Reports y Discovery: los **jars que había en tu equipo** (copiados en solo lectura).
- Intelligence: compilado desde el mismo código fuente.
- PostgreSQL 17.6 en contenedores desechables.
- Keycloak y Ollama: simulados. Keycloak, con el `nexora-realm.json` real y la misma semántica de los mappers; Ollama, con respuestas deterministas.
- Secretos y UUID: ficticios.

Resultado de la ejecución limpia:

- toda la secuencia del apartado 7 pasa (V0–V7, idempotencia, `-WhatIf`, restauración tras recrear Keycloak, `-Revert`);
- la única incidencia fue el `502` de Reports tras recrear Keycloak, ya documentado en la sección 3;
- un análisis de todos los registros e informes no encontró ningún secreto ni token.

Correcciones hechas a raíz de AUT-0:

- `Invoke-Int37Keycloak.ps1`: las búsquedas de clientes y usuarios trataban mal una lista de un solo elemento (desenrollado de arrays de PowerShell). `-Inspect` y `-Apply` habrían fallado también en Windows.
- `-Inspect` devuelve el código de salida 1 si el tenant es incoherente.
- `Update-Int37Credentials.ps1 -Check` detecta variables de entorno que anulan secretos, y muestra la fecha de rotación en ISO.
- Rutas relativas con `/` en todos los scripts (funcionan igual en Windows y Linux).
- Este README: reiniciar los servicios después de recrear Keycloak.

Lo que AUT-0 **no** puede comprobar y queda para Windows:

- el Keycloak 26.4 real: perfil de usuario, scopes integrados, atributos de roles y scopes, rotación de claves;
- `docker exec … psql` dentro del contenedor real (el contenedor del entorno aislado no trae `psql`; ver la sección 11);
- las ACL de Windows (`icacls`);
- la interpretación real de `gpt-oss:20b`;
- el rendimiento de tu máquina.

### Corrección del token de administración (diagnóstico del HTTP 401 en Windows)

**Problema.** `-Inspect` fallaba en Windows con `user e2e.runner -> HTTP 401`. El script pedía el token de administración (realm `master`, `admin-cli`, que caduca a los 60 s por defecto) una sola vez y lo reutilizaba. Si la ejecución pasaba de 60 s, las siguientes llamadas recibían 401.

**Corrección** (`Int37Common.psm1` e `Invoke-Int37Keycloak.ps1`):

- Una sesión de administración (`New-Int37AdminSession`, `Get-Int37AdminToken`, `Invoke-Int37Admin`) renueva el token cuando le quedan menos de 15 s, usando el `expires_in` de la respuesta.
- Ante un 401, reintenta como máximo una vez con un token nuevo. Si vuelve a recibir 401, devuelve ese error como antes.
- El token y la contraseña solo existen dentro de *closures*: no aparecen ni con `Format-List`, ni con `ConvertTo-Json`, ni en los errores.
- `-Inspect`, `-Apply`, `-Verify` y `-Revert` usan esa sesión para todas las llamadas de administración.

**Pruebas** (sin red): `pwsh -NoProfile -File ./tests/Int37AdminSession.Tests.ps1`. Son 24 comprobaciones de caducidad, renovación, reintento único, fallo persistente, estados que no se reintentan, ausencia de exposición y conexión con el script.

**AUT-0 repetido con una demora de 2,2 s por petición** en el simulador de Keycloak:

- secuencia completa en verde;
- `-Inspect` en 85 s con 2 tokens;
- `-Apply` en 146 s con 4 tokens;
- 0 respuestas 401 de la API de administración.

La evidencia está en `aut0/token-admin/`, junto con la reproducción del fallo antes de la corrección.

## 11. Pruebas aisladas de AUT-1b (PostgreSQL)

Ejecutadas en el entorno aislado de Claude, **con credenciales ficticias**. **No son una verificación en Windows**, y no se ha tocado ninguna base, contenedor ni `.env` de tu equipo. La evidencia está en `aut0/aut1b/`:

- `RESULTADOS.md`: resumen;
- `steps.log`: salida completa, sin secretos;
- `run-aut1b.sh` y `docker-shim.sh`: lo necesario para reproducirla.

**Pruebas sin Docker** (`pwsh -NoProfile -File ./tests/Int37PgRotation.Tests.ps1`): 42 comprobaciones.

- Verificador SCRAM idéntico a la referencia, sal aleatoria y rechazo de caracteres no ASCII.
- Orden del plan (superusuario el último) y rechazo de nombres de rol no válidos.
- Comprobaciones previas: sin acceso local, vía de verificación en `trust` (para todos los roles o solo para el superusuario), `.env` distinto de la base, diario pendiente, fallo al escribir el diario.
- Siete fallos en distintas etapas, con reversión automática completa y errores sin secretos.
- Contenedor caído durante la reversión (`ROLLBACK_FAILED`) y `-Recover` cuando vuelve.
- Interrupción en cada punto: tras escribir el diario, tras cada rol, tras el último rol, a mitad del `.env` y después del `.env`. `-Recover` deja todo en los valores anteriores, salvo en el último caso, en el que completa los nuevos.
- `-Recover` repetido, `-Recover` sin acceso local y separación de diarios de Keycloak y de PostgreSQL.

Las suites anteriores siguen en verde: 24 de la sesión de administración y 40 de la rotación de Keycloak.

**Integración contra PostgreSQL 17.6 real** (contenedores desechables con los nombres de tu equipo, `nexora-semantic-dev-postgres-1` en 5433 y `nexora-reports-postgres` en 5434; roles creados como en los `init`; las nueve credenciales con el mismo valor de 4 caracteres, como en tu `-Check`):

| Prueba | Resultado |
|---|---|
| `-WhatIf` de los dos destinos | No cambia nada; no crea diario |
| `-RotatePostgres -Target Semantic` | 3 roles cambiados y verificados por TCP; `.env` con valores de 32 caracteres; los anteriores ya no entran |
| Semantic, que seguía arrancado | Sigue `UP` con su pool; tras reiniciarlo, Flyway valida 9 migraciones con el propietario nuevo, y lectura (200) y escritura (201) con el pool nuevo |
| `-RotatePostgres -Target Reports` | Ídem con `nexora_reports_app`, `nexora_reader` y `nexora_reports_owner`; Reports arranca con los valores nuevos |
| `kill -9` real tras cambiar `nexora_semantic_owner` (aplicación y propietario ya cambiados, `.env` sin cambiar) | Diario `APPLYING`; `-Check` lo señala; una nueva rotación se niega; `-Recover` devuelve los 3 roles a los valores del `.env`; repetirlo no hace nada |
| `kill -9` real tras cambiar el superusuario de Reports (los 3 roles cambiados, `.env` sin cambiar) | Ídem: la recuperación entra por el *socket* sin conocer la contraseña nueva del superusuario |
| Secuencia completa: AUT-1a (`-Rotate`, Keycloak simulado), AUT-1b de los dos destinos e `-InitializeStore` | `-Check`: «Sin problemas» |
| `pg_hba` con `host all all all trust` | Se niega antes de tocar nada: «accepts a wrong password for nexora_reports_app» |
| Contenedor detenido | Se niega; el `.env` no cambia |
| Búsqueda de secretos en todas las salidas, registros y diarios | Ningún valor encontrado |

Diferencias con tu equipo, que solo se resuelven en Windows:

- La imagen del entorno aislado no trae `psql`. Un adaptador ejecuta el `psql` del anfitrión: por TCP con `-h` y por el *socket* del contenedor, montado en el anfitrión, sin `-h`. En tu equipo, `docker exec … psql` se ejecuta dentro del contenedor.
- Esa imagen tiene `scram-sha-256` para `127.0.0.1`; la oficial suele tener `trust`. El script no usa `127.0.0.1` y comprueba el método real con una contraseña falsa.
- Docker Desktop, las ACL de Windows y tus volúmenes con datos reales.

Corrección hecha a raíz de la integración: la prueba de contraseña falsa se hace para **cada rol**, porque una regla `pg_hba` puede confiar en un solo usuario o base.

## 12. Cierre de INT-3.7 (29 oct 2026)

Fuentes:

- informes de `%USERPROFILE%\.nexora\int37\out\`, leídos en solo lectura por Claude;
- resultados comunicados por el usuario (marcados **[U]**), que Claude no ha podido comprobar directamente.

Todas las ejecuciones usan la fuente `wellnode-sintetico` (`6b0de229-242d-4319-9446-e2fb018cb839`) del tenant `9126d6e6-295a-4f08-bec2-7c815123a949`. Son **datos sintéticos**: no hay datos de Wellnode.

### 12.1 Evidencias

| Prueba | Informe | Resultado |
|---|---|---|
| V1 · `mvn clean verify` de Intelligence | — **[U]** | BUILD SUCCESS: 541 pruebas, 0 fallos, 0 errores, 4 omitidas (`RealInferenceServerTest`, que solo se ejecuta con `NEXORA_REAL_INFERENCE_URL`); jar generado |
| V3 · SQL de referencia | `int37-20261029063102.md` | 3 PASS: P1 4 filas, P2 5 filas, P3 6 filas |
| V5 · plan manual + ejecución en Reports, sin modelo | `int37-20261029063209.md` | 3 PASS: filas iguales a lo esperado; ejecución de 48–57 ms |
| V6 · API de Intelligence con Ollama | `int37-20261029063008.md` | 5 PASS: P1–P3 iguales a lo esperado (12460, 4262 y 7679 ms), y negativos N1 (alias desconocido, 400 `validation_failed`) y N2 (JSON mal formado, 400 `malformed_request`) |
| V7 · primera ejecución (`-Repeat 3`) | `int37-20261029063237.md` | 2 PASS, 1 FAIL: P1 3/3, **P2 0/3 (HTTP 502)**, P3 3/3 |
| V7 · tras la corrección (`-Repeat 3`) | `int37-20261029072020.md` | **3 PASS, 9/9 `REPORTED`**, una sola huella por pregunta. Ver la tabla siguiente |

V7 final:

| Pregunta | Correctas | Media | Máximo |
|---|---|---|---|
| P1 | 3/3 | 1791 ms | 2329 ms |
| P2 | 3/3 | 1783 ms | 1821 ms |
| P3 | 3/3 | 5597 ms | 7522 ms |

**Coherencia entre pruebas.** Las huellas de plan de V6 coinciden con las de V5 en las tres preguntas:

- P1 `bc24f5ef…`;
- P2 `4451338a…`;
- P3 `b504c597…`.

El modelo produce el mismo plan que la construcción manual.

**Registros de Intelligence en V7 final [U]:**

- las nueve interpretaciones usaron `periodSchema=exact` y terminaron con `attempts=1`, sin reintentos;
- Semantic generó los planes y Reports los ejecutó con HTTP 200;
- P1 devolvió 4 filas, P2 5 y P3 6, sin truncamiento.

**Entorno [U]:**

- Ollama 0.40.12, `gpt-oss:20b`, contexto efectivo de 12288 tokens, 100 % GPU, motor llama.cpp;
- PostgreSQL 17.6 y Keycloak 26.4.0 en Docker.

**Sin informe disponible para Claude:**

- V0 (`-Inspect`), V2 (`-Verify`) y V4 (catálogo): sus resultados solo se ven en consola. V5–V7 no pasarían sin un catálogo aprobado y sin un token válido de `nexora-intelligence` para Semantic y Reports.
- El resultado de `-SharedReportsAudience Disable` y del `-Verify` posterior.
- El registro de Intelligence: lo anterior es lo que comunicó el usuario.

### 12.2 Estado de las autorizaciones de la preparación

| Id | Estado al cierre | Evidencia |
|---|---|---|
| AUT-0 | Completada | `aut0/RESULTADOS.md` (entorno aislado) |
| AUT-1a (Keycloak) | **Sin evidencia de ejecución** | `-Apply` exige que `KC_DEV_USER_PASSWORD`, `KC_REPORTS_CLIENT_SECRET` y `KC_BOOTSTRAP_ADMIN_PASSWORD` tengan al menos 24 caracteres y sean distintos, y terminó correctamente. Eso indica que ya no son los valores débiles, pero no prueba la rotación |
| AUT-1b (PostgreSQL) | **Sin evidencia de ejecución** | — |
| AUT-2 (`-Apply`) | Ejecutada | El usuario comunicó `-Apply` correcto (29 oct); V5–V7 lo confirman de forma indirecta |
| AUT-3 (base sintética) | Ejecutada | V3 |
| AUT-4 (catálogo) | Ejecutada | V5 y V6 (planes sobre las 7 definiciones) |
| AUT-5 (pruebas E2E) | Ejecutada | V5, V6 y V7 |

### 12.3 Fallo original de V7 (P2) y corrección

- **Síntoma:** P2 («¿Cuántos pozos hay por estatus del pozo?»), 0/3 con HTTP 502. En el registro: `INVALID_MODEL_OUTPUT`, `attempts=1`, `interpretation_invalid`, `period fields do not match its kind`. En V5 y V6, P2 había pasado.
- **Causa:** P2 no tiene rango de tiempo, así que la respuesta correcta lleva `period: null`. El modelo devolvió un `period` con un `kind` válido y campos que no le correspondían. La salida exacta no se registra, por diseño. Lo hicieron posible tres cosas:
  1. el esquema de salida declaraba un único objeto `period` con todos los campos anulables, y no podía expresar qué campos van con cada `kind`;
  2. el único ejemplo de petición interpretada en el prompt incluía un período;
  3. la inferencia no es totalmente determinista ni con `temperature: 0`, y una salida inválida no se volvía a pedir.
- **Cuatro correcciones** (Intelligence, paquete `infrastructure.inference`; sin cambios de API pública ni del formato de respuesta; el parser sigue igual de estricto):
  1. **Esquema exacto:** `period` es `null` o una de siete formas, una por `kind`. Cada forma tiene valor solo en sus campos y `null` en los demás, y el parser usa la misma tabla. Si no cabe en el contexto, se envía la forma compacta anterior (`periodSchema=compact`).
  2. **Prompt:** regla explícita de que `period` es `null` cuando la pregunta no pide un rango de tiempo, y un ejemplo ficticio con `period: null`.
  3. **Una sola repetición** (`invalid-output-retries`, 0–1, por defecto 1), solo para respuestas completas rechazadas por el parser. Lleva una nota fija con la regla incumplida y nunca la salida rechazada. Nunca se aplica a errores HTTP, tiempos agotados, respuestas truncadas o filtradas, respuestas demasiado grandes ni falta de contexto.
  4. **Diagnóstico:** el registro incluye la regla incumplida y la forma del período rechazado (su `kind` y los nombres de los campos con valor), nunca valores, pregunta ni salida.
- **Verificación de la corrección:**
  - pruebas automatizadas en Intelligence: 24 nuevas, 541 en total;
  - la gramática de llama.cpp generada con el esquema exacto rechaza las 8 formas inválidas y acepta las 9 válidas (`aut0/v7-periodo/RESULTADOS.md`);
  - V7 final 9/9 en Windows con `periodSchema=exact`, en un contexto de 12288 tokens.
- **Lo que no está validado contra Ollama real:** el reintento. En V7 no hizo falta ninguno (`attempts=1` en las nueve), así que solo está probado con el servidor simulado de las pruebas automatizadas.

### 12.4 Componentes integrados y validados

En la secuencia V4–V7 funcionaron juntos (V4 sin informe, ver 12.1):

- Discovery: metadatos de la base sintética, que Semantic importó para el catálogo (V4);
- Semantic: catálogo, 7 definiciones aprobadas y planes lógicos;
- Reports: ejecución de solo lectura sobre PostgreSQL;
- Intelligence: interpretación con `gpt-oss:20b`, plan y ejecución;
- Keycloak: identidad `nexora-intelligence`, con audiencias `nexora-semantic` y `nexora-reports`, roles `REPORTS_CLIENT` y `REPORT_RUNNER`, y tenant demo;
- PostgreSQL 17.6;
- Ollama.

### 12.5 Limitaciones y riesgos abiertos

- **Datos:** solo sintéticos. Los datos reales siguen bloqueados hasta la autorización escrita de Wellnode (compañía, pozos y periodo) y la confirmación de los tipos reales de `wm_well` y `bkw_electronic_logs`.
- **Muestra pequeña:** 3 repeticiones por pregunta y 3 preguntas, frente a las 5 del procedimiento. No demuestra fiabilidad estadística del modelo; con 9/9 solo se puede decir que no se observaron fallos en esta muestra. La variabilidad de `gpt-oss:20b` sigue siendo un riesgo, sobre todo con otras preguntas o catálogos.
- **Reintento:** sin validar contra Ollama real (12.3).
- **Contexto:** el esquema exacto necesita unos 12288 tokens con este catálogo. Con 8192 se envía el compacto, y la protección se limita al prompt, la validación estricta y el reintento. Un catálogo mayor reduce el margen.
- **Latencia:** P3 tarda unos 5,6 s de media (máximo 7,5 s) con GPU. Sin GPU o con otro equipo puede ser muy distinta.
- **Keycloak:** sin volumen; recrearlo borra la configuración de INT-3.7, los `sub` y las claves de firma (sección 3). La configuración no está en `nexora-realm.json`. El mapper compartido `nexora-reports-audience` queda desactivado, no borrado. `Invoke-R1Validation.ps1 -RevertKeycloakSetup` borra `REPORT_RUNNER`.
- **Credenciales:** no hay evidencia de AUT-1a ni de AUT-1b (12.2). Si las contraseñas de PostgreSQL siguen siendo las débiles y compartidas, el riesgo sigue abierto. Las contraseñas de los 5 usuarios de desarrollo siguen siendo una sola, compartida (sección 4.1, propuesta pendiente).
- **API de Intelligence:** sin autenticación de usuario final; solo loopback.
- **Capacidades del núcleo:** sin uniones, rankings, comparación de periodos, MIN/MAX ni duraciones. Tenant único de demo (opción A).
- **Persistencia del conocimiento:** AD-01 (fase 0) no implementada. Las definiciones y aprobaciones de la demo son de prueba.

### 12.6 Integración validada frente a preparación para producción

INT-3.7 demuestra que el flujo completo funciona en local con datos sintéticos:

> pregunta en lenguaje natural → Intelligence → Semantic → Reports → PostgreSQL → resultado vinculado al catálogo

**No** demuestra que esté preparado para producción. Faltan:

- datos reales autorizados;
- persistencia y trazabilidad del conocimiento (AD-01, fase 0);
- Keycloak persistente con configuración versionada;
- gestión de secretos fuera de ficheros `.env`;
- autenticación de usuario final;
- pruebas de carga y de fiabilidad del modelo con una muestra suficiente;
- monitorización;
- despliegue fuera del equipo de desarrollo;
- revisión de seguridad.

