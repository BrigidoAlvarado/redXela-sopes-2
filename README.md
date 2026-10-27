# REDXela

Proyecto final de Sistemas Operativos 2, CUNOC, segundo semestre de 2026.

El [enunciado](docs/Proyecto_Final_Sistemas_Operativos_2.md) es la fuente principal de requisitos. La [propuesta de arquitectura](PROPUESTA_ARQUITECTURA.md) define el diseño aprobado.

## Estado actual: F9

Implementados el arranque Spring Boot y Angular, el catálogo fijo de diez productos, los cinco tipos de mercancía, niveles de servicio, modelos de cliente/pedido, validaciones, creación y consulta en memoria, y rutas descriptivas por etapa.

El backend contiene un motor de simulación con inicio, pausa, reanudación y detención, un reloj simulado y trabajadores limitados. Los recursos físicos se controlan con semáforos y los pedidos se procesan concurrentemente por etapas. F4 permite seleccionar prioridad estricta, prioridad con aging o SJF y consultar la cola ordenada. El motor se crea detenido y los servicios están disponibles como beans de Spring.

F5 incorpora inventario en memoria inicialmente vacío, reservas atómicas, retiro por etapa, almacenamiento físico por ubicaciones y recepción mediante una cola acotada. Los pedidos sin existencias esperan sin ocupar trabajadores y se reactivan al recibir mercadería. Recepción y despacho comparten el escáner físico S01.

F6 agrega generación automática de pedidos con el reloj simulado y una demostración de deadlock real y controlada entre dos despachos. La demostración conserva C01 y S01 mediante semáforos reales, detecta la espera circular y requiere seleccionar manualmente la operación que continúa.

F7 incorpora métricas de pedidos, niveles de servicio y ocupación de recursos por corrida. También permite comparar las tres políticas mediante una carga fija ejecutada en instancias aisladas.

F8 expone una API REST bajo `/api` y snapshots mediante STOMP/WebSocket en `/ws`, canal `/topic/estado`.

F9 incorpora una interfaz Angular para controlar la simulación, registrar clientes, pedidos y recepciones, observar recursos, cambiar políticas, consultar métricas y resolver el deadlock real.

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

En Windows se utiliza `mvnw.cmd`. El servidor arranca en `http://localhost:8080`; la API comienza en `/api` y una consulta a `/` devuelve 404. El artefacto ejecutable se genera en `backend/target/redxela-0.1.0.jar`.

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

Si se utiliza nvm, `nvm install` y `nvm use` leen `.nvmrc`. La aplicación de desarrollo utiliza `http://127.0.0.1:4200` y se conecta al backend en `http://localhost:8080`.

## Motor de simulación

La configuración inicial en `backend/src/main/resources/application.yml` es de cuatro trabajadores y un factor de 60: un segundo real equivale a 60 segundos simulados. Ambos valores pueden modificarse antes de arrancar Spring; la velocidad también puede cambiarse a través del servicio Java durante una corrida.

El [manual técnico de F9](docs/manual-tecnico/F9.md) explica la interfaz y su ejecución. El [manual de F8](docs/manual-tecnico/F8.md) documenta endpoints, JSON y WebSocket. Los manuales de [F7](docs/manual-tecnico/F7.md), [F6](docs/manual-tecnico/F6.md), [F5](docs/manual-tecnico/F5.md), [F4](docs/manual-tecnico/F4.md), [F3](docs/manual-tecnico/F3.md), [F2](docs/manual-tecnico/F2.md) y [F1](docs/manual-tecnico/F1.md) detallan las fases anteriores.

La implementación se detiene en F9 hasta su revisión.
