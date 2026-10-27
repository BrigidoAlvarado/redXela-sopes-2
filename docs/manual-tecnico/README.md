# REDXela — Manual Técnico

**Universidad de San Carlos de Guatemala**

Centro Universitario de Occidente · División de Ciencias de la Ingeniería

Carrera de Ciencias y Sistemas · Curso Sistemas Operativos 2

**Proyecto REDXela · Segundo semestre de 2026**

**Docente:** Ing. Otto Soto

**Versión documental:** 1.0 · 27 de octubre de 2026

## Alcance y procedencia

Este es el Manual Técnico oficial y autocontenido. Explica la implementación de `main` en el commit `5466e58`, base de la rama `docs/manual-tecnico-completo`. Esa base ya incluye el Manual de Usuario aprobado. Los ejemplos numéricos identificados como didácticos explican el código; no son mediciones de una ejecución.

El commit base eliminó el enunciado, el informe F10 y la guía de demostración. Se consultaron íntegramente sus versiones anteriores en Git: [enunciado original](https://github.com/BrigidoAlvarado/redXela-sopes-2/blob/86adb90/docs/Proyecto_Final_Sistemas_Operativos_2.md), [informe de verificación F10](https://github.com/BrigidoAlvarado/redXela-sopes-2/blob/86adb90/docs/INFORME_VERIFICACION_FINAL.md) y [guía de demostración](https://github.com/BrigidoAlvarado/redXela-sopes-2/blob/86adb90/docs/GUIA_DEMOSTRACION.md). No se presuponen presentes en el árbol actual. `PROPUESTA_ARQUITECTURA.md` tampoco está en esta base; no se usa para atribuir funcionalidades al sistema. Las comparaciones con alternativas son argumentos técnicos, no una reconstrucción de decisiones históricas.

Se contrastaron código de producción backend, configuración, pruebas relevantes, servicios y pantallas Angular, README, Manual de Usuario y documentos históricos F1–F9. Estos últimos pueden describir alcances anteriores: cuando discrepan, aquí se describe el código actual. Las rutas enlazadas son relativas al repositorio y los nombres Java pertenecen a `gt.edu.usac.cunoc.redxela`, salvo indicación distinta.

## Índice

1. [Introducción](#1-introducción)
2. [Análisis del problema y requisitos](#2-análisis-del-problema-y-requisitos)
3. [Arquitectura general](#3-arquitectura-general)
4. [Infraestructura y tecnologías](#4-infraestructura-y-tecnologías)
5. [Modelo de dominio](#5-modelo-de-dominio)
6. [Motor de simulación](#6-motor-de-simulación)
7. [Concurrencia y administración de hilos](#7-concurrencia-y-administración-de-hilos)
8. [Semáforos y exclusión mutua](#8-semáforos-y-exclusión-mutua)
9. [Planificación de pedidos](#9-planificación-de-pedidos)
10. [Procesamiento de pedidos](#10-procesamiento-de-pedidos)
11. [Productor-consumidor y comunicación entre áreas](#11-productor-consumidor-y-comunicación-entre-áreas)
12. [Inventario y reservas](#12-inventario-y-reservas)
13. [Administración de almacenamiento](#13-administración-de-almacenamiento)
14. [Deadlock: generación, detección y recuperación](#14-deadlock-generación-detección-y-recuperación)
15. [Métricas y comparación de políticas](#15-métricas-y-comparación-de-políticas)
16. [API REST y WebSocket](#16-api-rest-y-websocket)
17. [Arquitectura Angular](#17-arquitectura-angular)
18. [Seguridad de concurrencia y manejo de excepciones](#18-seguridad-de-concurrencia-y-manejo-de-excepciones)
19. [Pruebas y validación](#19-pruebas-y-validación)
20. [Limitaciones y decisiones de alcance](#20-limitaciones-y-decisiones-de-alcance)
21. [Conclusiones técnicas](#21-conclusiones-técnicas)
22. [Matriz de decisiones de diseño](#22-matriz-de-decisiones-de-diseño)
23. [Trazabilidad contra el enunciado](#23-trazabilidad-contra-el-enunciado)
24. [Índice de evidencias y referencias](#24-índice-de-evidencias-y-referencias)

## 1. Introducción

El problema de REDXela es coordinar pedidos de tamaños, servicios y mercancías distintos que compiten por equipos y personas limitados. Recibir mercancía, reservar stock y despachar ocurren concurrentemente; una comprobación seguida de una modificación sin coordinación puede asignar dos veces una unidad de stock o un equipo. Además, adquirir recursos en distinto orden puede detener dos despachos por espera circular.

El sistema representa ese problema con tareas Java reales, planificación de etapas, semáforos por unidad física, exclusión mutua sobre inventario, mensajes entre áreas y un escenario controlado de deadlock. Angular permite observarlo y enviar acciones al backend. El objetivo académico es explicar qué tarea puede progresar, por qué espera y cómo se conserva la consistencia al pausar, detener o recuperar un conflicto.

Una política de atención expresa preferencia; no crea stock ni equipos. Un objetivo de servicio es una meta medible; no garantiza cumplimiento cuando la demanda supera la capacidad. La separación entre espera, procesamiento y ocupación permite mostrar esas diferencias.

## 2. Análisis del problema y requisitos

La siguiente matriz fue contrastada con la implementación antes de redactar las explicaciones. **O** significa obligación del enunciado; **D** identifica una decisión cuyo valor o mecanismo concreto quedó a criterio del desarrollo.

| Requisito | Problema técnico | Solución implementada | Evidencia |
| --- | --- | --- | --- |
| O: múltiples pedidos concurrentes | Trabajo simultáneo con capacidad finita | Tareas por etapa en `ThreadPoolExecutor`; espera fuera del ejecutor | `ProcesadorPedidosTest`, `EjecutorTrabajos` |
| O: cantidades exactas de recursos | Impedir uso simultáneo de una misma unidad | 26 semáforos binarios en `GestorRecursos` | `TipoRecurso`, `GestorRecursosTest` |
| O: clientes, pedidos manuales y cinco servicios | Validar identidad y clasificar atención | Registros inmutables, UUID, validaciones y formularios | `ClienteServiceTest`, `PedidoServiceTest`, `ApiIntegracionTest` |
| O: generación continua de clientes y pedidos | Carga sin un hilo por llegada | Un generador, clientes en su servicio y mismo procesador | `GeneradorPedidosTest` |
| O: comparar políticas de atención | Resultados con carga equivalente | PRIORIDAD, AGING y SJF; escenario fijo aislado | `PlanificadorPedidosTest`, `ComparacionPoliticasTest` |
| O: unidades como estimación de trabajo | Duraciones relacionadas con carga | Duración por paso proporcional a sus unidades | `ProcesadorPedidos.duracion` |
| O: mínimo cinco mercancías y perfiles distintos | Separar cuándo atender de qué recursos usar | Cinco tipos con mapa de necesidades por etapa | `TipoMercancia`, `CatalogoTest` |
| O: dos áreas independientes coordinadas | Comunicar sin escribir estructuras ajenas | Mensajes de recepción en cola acotada, consumidor de inventario | `RecepcionService`, `InventarioIntegracionTest` |
| O: no perder datos ante producción rápida | Presión sobre consumidores y almacén | Límite de 100 recepciones no terminales y rechazo de nuevas solicitudes | `colaAcotadaYContencionDelUnicoEscaner` |
| O: catálogo predefinido y recepción automática | Identificar producto y actualizar el sistema | Catálogo de diez productos, escaneo, cola, almacenamiento y aviso | `CatalogoProductos`, `RecepcionService.consumir` |
| O: lotes en posiciones no contiguas | Fragmentación y capacidad | Fragmentos por lote/producto en ubicaciones; First Fit completo | `loteFragmentadoEntreHuecosNoContiguosSinExcederCapacidad` |
| O: provocar, explicar y resolver bloqueo circular | Deadlock real entre operaciones | Despachos con C01/S01, adquisición inversa, detector específico y elección manual | `DemostracionDeadlockTest`, `DeadlockPage` |
| O: interfaz en tiempo real, cola, progreso y métricas | Observar estado backend sin duplicar lógica | REST, STOMP, seis vistas y métricas bajo demanda | `ApiIntegracionTest`, pruebas Angular |
| O: backend Java, sincronización real, comentarios y excepciones | Corrección y defensa de fundamentos | Locks, monitores, semáforos, interrupciones y pruebas | Secciones 6–19 |
| O: fuente y ambos manuales | Entrega comprensible | Código versionado, este documento y Manual de Usuario | Sección 23 |
| D: reloj y ciclo de vida | Acelerar demostración y pausar coherentemente | Un reloj monotónico, factor inicial 60, pausa cooperativa | `RelojSimuladoTest`, `MotorSimulacionTest` |
| D: capacidad y distribución | Concretar el almacén permitido por el enunciado | 5 × 5 × 4 ubicaciones de volumen 10 | `InventarioService` |
| D: objetivo Exprés | Convertir «casi instantáneo» a comparación numérica | 30 segundos simulados; restantes objetivos 10/30/60/120 minutos | `NivelServicio`, `MetricasService` |

Las obligaciones no imponen Spring Boot, Angular, base de datos ni microservicios. La pila elegida satisface la aplicación web y conserva el trabajo concurrente en Java. El registro manual operativo se permite con el motor ejecutándose; durante pausa o detención se rechaza, interpretación explícita del control de simulación, no disponibilidad irrestricta en cualquier estado.

## 3. Arquitectura general

### Problema, fundamento y solución

El centro necesita un estado compartido que distintos hilos puedan consultar y modificar consistentemente. Se usa un **monolito modular**: una JVM de Spring conecta servicios Java mediante beans, y una aplicación Angular sirve de interfaz. La modularidad separa dominio, motor, recursos, recepción, inventario, planificación, métricas y API sin convertirlos en procesos distribuidos.

<!-- DIAGRAMA: arquitectura -->

La arquitectura coloca todas las decisiones operativas en la JVM. Un navegador no controla la exclusión mutua de otro navegador ni debe convertirse en una segunda fuente de inventario. Las peticiones HTTP pueden llegar a la vez desde distintas vistas o clientes; los servicios coordinan ese acceso.

### Justificación y alternativas

Un monolito permite que un único monitor proteja reserva y almacenamiento y que los semáforos representen las unidades auténticas para todas las áreas. Microservicios exigirían coordinación remota, fallos parciales y transacciones adicionales sin resolver mejor el objetivo académico. Una aplicación de escritorio Java también sería viable para el enunciado, pero la pila web permite separar presentación y ejecución con acceso desde un navegador.

REST proporciona respuestas explícitas para comandos y consultas; STOMP sobre WebSocket difunde instantáneas de observación. Usar únicamente sondeo HTTP funcionaría con más consultas, pero no aprovecharía el canal de actualizaciones. WebSocket como único canal exigiría definir correlación, respuestas y errores de todos los comandos; aquí ese contrato ya lo aporta HTTP.

El estado en memoria evita que una base de datos o transacciones SQL oculten la sincronización que se desea estudiar. Su compromiso es la pérdida de datos al cerrar la aplicación, consumo de memoria durante cargas largas y ausencia de durabilidad. No equivale a un sistema logístico de producción.

### Interacción y evidencia

Una acción sigue Angular → controlador → servicio. El controlador traduce solicitudes y errores, no reimplementa algoritmos. `DominioConfiguration`, `SimulacionConfiguration` y `ProcesamientoConfiguration` conectan y cierran los servicios. `PublicadorEstado` reúne vistas para STOMP. La evidencia está en esas configuraciones, `ApiController`, `ComunicacionConfig`, `main.ts` y `ApiIntegracionTest`; véanse los enlaces de la sección 24.

## 4. Infraestructura y tecnologías

| Tecnología y versión declarada | Propósito | Configuración comprobada |
| --- | --- | --- |
| Java 21 | Hilos, monitores, semáforos, dominio y ejecutores | `backend/pom.xml`, `java.version=21` |
| Spring Boot 4.1.2 | Inyección, servidor HTTP, serialización y WebSocket | Parent del POM; starters webmvc y websocket |
| Maven 3.9.16 / Wrapper 3.3.4 | Compilación, pruebas y empaquetado reproducible | `backend/.mvn/wrapper/maven-wrapper.properties` |
| Angular y CLI 21.2.0 | Componentes standalone, rutas y presentación | `frontend/package.json` |
| TypeScript 5.9.3 | Contratos y comprobación estática | `package.json`, `tsconfig.json` con modo estricto |
| Node.js 24; `.nvmrc` 24.22.1 | Herramientas de instalación y compilación Angular | `engines >=24.0.0 <25` |
| STOMP JS 7.2.1 y RxJS 7.8.2 | Canal STOMP y peticiones HTTP con timeout | `package.json`, servicios frontend |
| Memoria principal | Registros, colas, reservas y ubicaciones | Colecciones de servicios; no driver de BD |

Son versiones fijadas en el repositorio; no se afirma aquí una auditoría externa del soporte vigente. HTTP transporta JSON, WebSocket mantiene una conexión bidireccional y STOMP añade destinos de suscripción. El broker simple está dentro de Spring, sin RabbitMQ ni Kafka.

### Despliegue lógico

<!-- DIAGRAMA: despliegue -->

Angular se ejecuta en el navegador y Node sirve los archivos durante el desarrollo; el negocio se ejecuta en la JVM de Spring. Ambos servidores pueden estar en una misma computadora. Los hilos de Recepción y Control de Inventario pertenecen a esa misma JVM; no son dos procesos del sistema operativo. La comparación crea instancias Java aisladas dentro del proceso, no contenedores ni JVM adicionales.

### Compilación y ejecución

Requiere JDK 21 (`JAVA_HOME` correcto), Node 24, npm y red en la primera descarga. Desde la raíz, en terminales distintas:

```bash
cd backend
./mvnw verify
./mvnw spring-boot:run
```

```bash
cd frontend
npm ci
npm test
npm run build
npm start
```

En Windows se usa `mvnw.cmd`. Abrir `http://127.0.0.1:4200`; el backend escucha en `http://localhost:8080`. La raíz HTTP del backend no es una página: consultar `/api/simulacion`. El JAR se genera en `backend/target/redxela-0.1.0.jar`; se puede ejecutar con `java -jar backend/target/redxela-0.1.0.jar`. La salida Angular está en `frontend/dist/redxela/browser/`; no se configura aquí un despliegue productivo de esos estáticos.

`application.yml` fija cuatro trabajadores y factor 60. Los trabajadores se configuran al crear el bean; la velocidad cambia también durante la corrida. CORS permite los dos orígenes locales de puerto 4200, por lo que cambiar puertos o desplegar en otro origen exige ajustar configuración. No hay autenticación; CORS no sustituye control de acceso.

```text
backend/src/main/java/gt/edu/usac/cunoc/redxela/
  api/ configuracion/           Integración Spring y contratos
  catalogo/ clientes/ pedidos/  Dominio, generación y ejecución
  simulacion/ planificacion/   Ciclo de vida, reloj y comparación de candidatos
  recursos/                    Unidades físicas y permisos
  recepcion/ inventario/        Canal entre áreas y almacén
  conflicto/ metricas/          Deadlock y consultas estadísticas
backend/src/test/java/          Pruebas JUnit
frontend/src/app/core/          REST, STOMP, estado y contratos
frontend/src/app/pages/         Seis vistas operativas
frontend/src/app/app.routes.ts  Navegación
```

### Compromisos y evidencia

Spring reduce código de infraestructura y permite probar servicios sin levantar HTTP; usar sockets manuales obligaría a implementar un protocolo innecesario. Angular y TypeScript aportan componentes y contratos, con costo de una cadena de compilación separada. Maven Wrapper evita depender del Maven global, aunque la primera ejecución requiere descargas. `package-lock.json` fija la instalación npm. `.gitignore` excluye `target`, `node_modules`, `dist` y cachés Angular.

## 5. Modelo de dominio

### Datos y relaciones

El dominio utiliza records inmutables y copias defensivas para evitar cambios concurrentes en una solicitud ya admitida. `PedidoService` almacena pedidos en `ConcurrentHashMap`; `AtomicLong` asigna `secuenciaEntrada`, usada como desempate y no como tiempo. `ClienteService` protege con un mismo monitor sus índices por UUID y correo. Normaliza el correo en minúsculas, exige formato básico y evita duplicados; no verifica buzones ni implementa cuentas autenticadas.

<!-- DIAGRAMA: dominio -->

Este UML es un recorte del modelo: separa la descripción `PlanTrabajo` del progreso interno `Ejecucion` de `ProcesadorPedidos`. No representa todos los métodos ni los DTO de HTTP. Un pedido tiene un cliente, un nivel contratado y varias líneas; cada producto tiene su tipo y volumen. El nivel preferido del cliente no obliga al nivel del pedido.

| Elemento | Atributos o reglas principales |
| --- | --- |
| `Cliente` | `UUID id`, nombre, correo, `servicioPreferido`; campos requeridos |
| `Pedido` | UUID, cliente, nivel, lista defensiva de líneas, secuencia positiva; sin productos repetidos |
| `LineaPedido` | Producto canónico, cantidad `int > 0`; `volumenTotal()` usa `long` |
| `Producto` | Código normalizado, nombre, tipo, volumen entero positivo |
| `PlanTrabajo` | ID y lista inmutable de pasos; se construye una vez al admitir |
| `PasoTrabajo` | Posición, etapa, tipo y unidades agrupadas de ese tipo |
| `Ejecucion` interna | Corrida, plan, completados, estado, tiempos, asignados, banderas de admisión y conflicto, futuro |

`Pedido.estado()` devuelve siempre `CREADO`: es un registro de entrada. La fuente de estado operativo es `ProcesadorPedidos.consultar`, que produce `Progreso`. Exponer solo el record inicial daría una imagen equivocada del procesamiento.

### Catálogo y servicios

El catálogo es predefinido por requisito. Mantenerlo inmutable evita que una edición cambie el volumen o los recursos necesarios de un pedido activo. Permitir alta dinámica demandaría reglas adicionales de compatibilidad; no está implementado.

| Código | Nombre real | Tipo | Volumen por unidad |
| --- | --- | --- | --- |
| GEN-01 | Cuaderno universitario | GENERAL | 1 |
| GEN-02 | Paquete de camisetas | GENERAL | 1 |
| FRA-01 | Juego de vasos | FRAGIL | 2 |
| FRA-02 | Lámpara de vidrio | FRAGIL | 2 |
| PES-01 | Caja de herramientas | PESADO | 3 |
| PES-02 | Saco de material | PESADO | 4 |
| VOL-01 | Silla empacada | VOLUMINOSO | 5 |
| VOL-02 | Mesa plegable | VOLUMINOSO | 5 |
| ELE-01 | Equipo de cómputo paletizado | ELECTRONICA_PALETIZADA | 4 |
| ELE-02 | Equipo de sonido paletizado | ELECTRONICA_PALETIZADA | 3 |

**Nivel de servicio** decide preferencia y objetivo de primera atención. **Tipo de mercancía** decide recursos y etapas; ambas clasificaciones son independientes.

| Nivel | Enumeración | Objetivo simulado |
| --- | --- | --- |
| 1 | EXPRES | 30 s, decisión para concretar «casi instantáneo» |
| 2 | PRIORITARIO | 10 min |
| 3 | ESTANDAR | 30 min |
| 4 | PROGRAMADO | 60 min |
| 5 | ECONOMICO | 120 min |

Las etapas están ordenadas `RETIRO`, `EMPAQUE`, `CALIDAD`, `DESPACHO`. Calidad se omite si el tipo no la requiere. El plan agrupa unidades por tipo y recorre primero todas las retiradas, después empaques, calidades y despachos. No crea un paso por artículo.

### Perfiles de mercancía

B = encargado de bodega; E = empaque; M = montacargas; Q = calidad; C = carga; S = escáner. Las cantidades son **simultáneas por paso**, no equipos multiplicados por unidades de mercancía.

| Tipo y característica | Retiro | Empaque | Calidad | Despacho |
| --- | --- | --- | --- | --- |
| GENERAL: artículos pequeños ordinarios | B×1 | B×1, E×1 | — | B×1, C×1, S×1 |
| FRAGIL: protección y revisión de integridad | B×1 | B×1, E×1 | B×1, Q×1 | B×1, C×1, S×1 |
| PESADO: movimiento mecánico | B×2, M×1 | B×1, E×1 | — | B×2, M×1, C×1, S×1 |
| VOLUMINOSO: dos superficies de empaque | B×2, M×1 | B×2, E×2 | — | B×2, M×1, C×1, S×1 |
| ELECTRONICA_PALETIZADA: pallet y verificación funcional | B×1, M×1 | B×1, E×1 | B×1, Q×1 | B×1, M×1, C×1, S×1 |

Los perfiles completos son distintos aunque compartan pasos. Esto modela contención realista sin cambiar la prioridad por tipo. `RequisitosRecursos` valida que cada cantidad sea positiva y no supere la capacidad.

### Riesgos, alternativas y evidencia

Usar objetos mutables con setters permitiría cambiar una línea mientras se reserva; las copias inmutables reducen ese riesgo. La inmutabilidad no protege por sí sola el progreso ni las reservas, que necesitan monitores. Un pedido válido puede pedir más stock del que alguna vez podrá reservarse en este almacén: el dominio no garantiza viabilidad logística futura. `CatalogoTest`, `ClienteServiceTest`, `PedidoServiceTest` y `PlanTrabajoTest` verifican catálogo, perfiles, duplicados, registros concurrentes, copias y agrupación.
