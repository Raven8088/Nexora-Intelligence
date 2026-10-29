# V7 · P2 «period fields do not match its kind»: corrección y pruebas

Entorno aislado de Claude, 29 de octubre de 2026. **No es una verificación en Windows** ni contra `gpt-oss:20b` real.

## `mvn clean verify` (Nexora Intelligence)

541 pruebas, 0 fallos, 0 errores, 4 omitidas (`RealInferenceServerTest`, que necesita Ollama). Antes eran 517 pruebas y 3 omitidas.

## Compatibilidad del esquema con la gramática de Ollama/llama.cpp

`schema2gbnf.cpp` convierte el esquema JSON en gramática con `json_schema_to_grammar` de llama.cpp, el conversor que usa Ollama (copia de llama-cpp-python 0.3.36). `test-gbnf-validator` comprueba después cada respuesta de ejemplo contra la gramática (`validacion-gramatica.txt`).

- **Esquema exacto:** se convierte sin errores. Acepta las 9 respuestas válidas: P2 con `period: null`, P3 como `RANGE` y una por cada tipo. Rechaza las 8 inválidas, incluida la de V7 (`RANGE` con `from`/`to` a `null`) y un año fuera de rango.
- **Esquema compacto** (el anterior): acepta 7 de las 8 inválidas. Solo rechaza el año fuera de rango. Es exactamente lo que dejó pasar V7, y solo lo frenaba el parser.

## Limitación encontrada: tamaño

Las formas exactas añaden unos 900 tokens estimados. Con `context-tokens` 8192 y `max-tokens` 4096, un catálogo como el de la demo ya no cabe: 3504 tokens frente a los 3277 disponibles. En ese caso se envía la forma compacta y el registro lo indica con `periodSchema=compact`. La validación estricta y la repetición única se aplican igual en los dos casos.

Para usar la forma exacta en Windows hay que subir a 12288 tanto `OLLAMA_CONTEXT_LENGTH` como `NEXORA_INTELLIGENCE_INFERENCE_CONTEXT_TOKENS`. Requiere reiniciar Ollama y tu autorización.
