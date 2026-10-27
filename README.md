# Nexora Intelligence

Microservicio de Nexora AI que interpretará preguntas en lenguaje natural y las convertirá en solicitudes estructuradas para Nexora Semantic.

- Requisitos: Java 21 y Maven 3.9+.
- Compilar y probar: `mvn clean verify`
- Ejecutar: `mvn spring-boot:run` (puerto predeterminado `8100`; cambiarlo con la variable `INTELLIGENCE_PORT`).
- Salud: `GET /actuator/health`

Estado: INT-1 (fundación). Contratos de dominio y puerto `QuestionInterpreter` definidos; todavía no interpreta preguntas ni integra ningún modelo de IA.
