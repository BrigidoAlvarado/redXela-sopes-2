# REDXela

Proyecto final de Sistemas Operativos 2, CUNOC, segundo semestre de 2026.

El [enunciado](.docs/Proyecto_Final_Sistemas_Operativos_2.md) es la fuente principal de requisitos. La [propuesta de arquitectura](PROPUESTA_ARQUITECTURA.md) define el diseño aprobado.

## Estado actual: F2

Implementados el arranque Spring Boot y Angular, el catálogo fijo de diez productos, los cinco tipos de mercancía, niveles de servicio, modelos de cliente/pedido, validaciones, creación y consulta en memoria, y rutas descriptivas por etapa.

El backend también contiene un motor de simulación con inicio, pausa, reanudación y detención, un reloj simulado y trabajadores limitados para tareas cooperativas. El motor se crea detenido y todavía no procesa pedidos. Sus servicios Java están conectados como beans de Spring y se verifican mediante pruebas.

Angular contiene únicamente la aplicación raíz standalone. Aún no hay API de negocio, formularios operativos, generación, planificación, procesamiento de pedidos, inventario, semáforos ni deadlock ejecutable: pertenecen a las siguientes fases. Las capacidades e identificadores C01/S01 están definidos en el modelo sin asignaciones ficticias.

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

En Windows se utiliza `mvnw.cmd`. El servidor arranca en `http://localhost:8080`; todavía no expone endpoints de negocio, por lo que una consulta a `/` devuelve 404. El artefacto ejecutable se genera en `backend/target/redxela-0.1.0.jar`.

El `pom.xml` se puede importar como proyecto Maven en IntelliJ, seleccionando JDK 21 y Maven Wrapper.
En la terminal, `./mvnw -version` muestra el JDK que utiliza Maven; si `JAVA_HOME` está definido, debe apuntar al JDK 21.

## Frontend

Desde `frontend/`, con Node 24 activo:

```bash
npm ci
npm test
npm run build
npm start
```

Si se utiliza nvm, `nvm install` y `nvm use` leen `.nvmrc`. La aplicación de desarrollo utiliza `http://127.0.0.1:4200`. No hay peticiones al motor desde Angular en F2.

## Motor de simulación

La configuración inicial en `backend/src/main/resources/application.yml` es de cuatro trabajadores y un factor de 60: un segundo real equivale a 60 segundos simulados. Ambos valores pueden modificarse antes de arrancar Spring; la velocidad también puede cambiarse a través del servicio Java durante una corrida.

Por ahora, el motor se utiliza desde código Java y pruebas; no hay controles REST ni interfaz gráfica. [El manual técnico de F2](docs/manual-tecnico/F2.md) explica el contrato cooperativo, los estados y la detención segura. La [documentación de F1](docs/manual-tecnico/F1.md) conserva el detalle del dominio y las definiciones de tiempos de espera.

La implementación se detiene en F2 hasta su revisión. Las rutas de trabajo de F1 siguen siendo descriptivas y aún no ejecutan pedidos.
