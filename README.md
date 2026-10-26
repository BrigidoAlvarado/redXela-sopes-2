# REDXela

Proyecto final de Sistemas Operativos 2, CUNOC, segundo semestre de 2026.

El [enunciado](.docs/Proyecto_Final_Sistemas_Operativos_2.md) es la fuente principal de requisitos. La [propuesta de arquitectura](PROPUESTA_ARQUITECTURA.md) define el diseño aprobado.

## Estado actual: F1

Implementados el arranque Spring Boot y Angular, el catálogo fijo de diez productos, los cinco tipos de mercancía, niveles de servicio, modelos de cliente/pedido, validaciones, creación y consulta en memoria, y rutas descriptivas por etapa.

Los servicios Java están conectados como beans de Spring y son verificables mediante pruebas. Angular contiene únicamente la aplicación raíz standalone. Aún no hay API de negocio, formularios operativos, reloj, generación, planificación, procesamiento, inventario, semáforos ni deadlock ejecutable: pertenecen a las siguientes fases. Las capacidades e identificadores C01/S01 ya están definidos en el modelo sin asignaciones ficticias.

## Requisitos

- JDK 21.
- Maven 3.9.16 mediante el Wrapper incluido; no es necesario instalar Maven globalmente.
- Node.js 24.22.1 LTS, indicado en `frontend/.nvmrc`; npm incluido en esa distribución.
- Spring Boot 4.1.2 y Angular/CLI 21.2.0, fijados en sus manifiestos.

La primera ejecución necesita acceso a Maven Central y al registro npm para descargar las dependencias. El proyecto no requiere base de datos.

## Backend

Desde `backend/`:

```bash
./mvnw verify
./mvnw spring-boot:run
```

En Windows se utiliza `mvnw.cmd`. El servidor arranca en `http://localhost:8080`; en F1 no expone endpoints de negocio, por lo que una consulta a `/` devuelve 404. El artefacto ejecutable se genera en `backend/target/redxela-0.1.0.jar`.

El `pom.xml` se puede importar como proyecto Maven en IntelliJ, seleccionando JDK 21 y Maven Wrapper.

## Frontend

Desde `frontend/`, con Node 24 activo:

```bash
npm ci
npm test
npm run build
npm start
```

Si se utiliza nvm, `nvm install` y `nvm use` leen `.nvmrc`. La aplicación de desarrollo utiliza `http://127.0.0.1:4200`. No hay peticiones a un backend de simulación en F1.

## Documentación de F1

[Alcance, modelos, validaciones, semántica de tiempos y verificaciones](docs/manual-tecnico/F1.md).

La implementación se detiene en F1 hasta su revisión. La lista de estados y la ruta de trabajo describen el dominio; no representan una simulación ya implementada.
