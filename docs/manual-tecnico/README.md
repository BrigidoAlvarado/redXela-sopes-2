# Manual técnico de REDXela

REDXela es un monolito Spring Boot con una aplicación Angular standalone. El estado vive en memoria. El navegador invoca la API REST; una conexión STOMP sobre WebSocket recibe instantáneas en `/topic/estado`. El backend conserva la planificación, el inventario, los semáforos y la detección del conflicto. El contrato y los endpoints están en [F8](F8.md); la estructura de Angular, en [F9](F9.md).

## Flujo y componentes

`ClienteService` y `PedidoService` validan y registran datos. `PlanTrabajo` describe etapas sin almacenar progreso; `ProcesadorPedidos` coordina el progreso, reserva existencias y planifica una etapa a la vez. Un pedido sin inventario espera fuera del ejecutor. Tras reservar, compite por los recursos de cada etapa; al retirar la mercancía se descuenta una sola vez, y al completar cada etapa se liberan recursos. Las políticas `PRIORIDAD`, `AGING` y `SJF` se explican en [F4](F4.md).

`RecepcionService` y `InventarioService` representan áreas independientes de la misma JVM, no procesos del sistema operativo separados. Recepción comparte el escáner S01 con despacho y publica un mensaje en una `ArrayBlockingQueue` acotada. Inventario consume el mensaje y registra el lote en ubicaciones usando first fit. Reservas y almacenamiento se coordinan bajo exclusión mutua; la reserva de un pedido es todo o nada. Consulte [F5](F5.md).

## Conceptos de sistemas operativos

- **Hilos y ejecutores:** `MotorSimulacion` y `EjecutorTrabajos` limitan trabajadores. El generador y los coordinadores tienen funciones específicas; no se crea un hilo permanente por pedido.
- **Planificación:** `PlanificadorPedidos` selecciona entre candidatos según prioridad, prioridad con envejecimiento o unidades (SJF). Los desempates usan secuencia de llegada; una etapa activa no se expropia.
- **Semáforos:** `GestorRecursos` controla las unidades físicas, sus propietarios y esperas. La operación normal adquiere la combinación completa o devuelve los permisos parciales. Se usa `synchronized` para proteger secciones críticas y estructuras compartidas.
- **Sincronización y tiempo:** `MotorSimulacion` utiliza `ReentrantLock` y `Condition` para pausar cooperativamente. `RelojSimulado` usa `System.nanoTime()` y congela el tiempo durante la pausa. El factor inicial es 60.
- **Productor-consumidor:** `BlockingQueue` transporta mensajes entre Recepción e Inventario. Una cola llena rechaza solicitudes nuevas sin perder las aceptadas.
- **Deadlock:** `DemostracionDeadlock` usa semáforos reales C01 y S01; `CountDownLatch` coordina la adquisición inversa. La exclusión mutua, retención y espera, ausencia de expropiación automática y espera circular satisfacen las cuatro condiciones de Coffman. El detector comprueba propietarios y esperas reales. La elección manual interrumpe cooperativamente al perdedor, quien libera sus recursos y vuelve a esperar; el ganador continúa. Véase [F6](F6.md).
- **Almacenamiento:** 100 ubicaciones de 10 unidades de volumen. Un lote puede ocupar varios fragmentos; si no cabe, espera sin crear existencias ficticias. La disponibilidad es físico menos reservado.

Spring Boot integra y expone los servicios; Angular presenta su estado sin reproducir la lógica de concurrencia. REST permite operaciones y consultas; WebSocket ofrece instantáneas periódicas. No hay persistencia ni mensajería externa.

## Métricas y límites

Primera atención es el intervalo entre admisión y primer trabajo efectivo; la espera acumulada incluye esperas posteriores. Procesamiento suma el trabajo efectivo. Los objetivos de servicio usan primera atención; Exprés adopta 30 segundos simulados como interpretación de «casi instantáneo». Utilización = tiempo ocupado de todas las unidades / (unidades × tiempo observado). Comparación usa el mismo escenario en instancias aisladas; los resultados pueden variar por concurrencia. Fórmulas y alcance en [F7](F7.md).

## Referencias por fase

[F1: dominio](F1.md) · [F2: motor](F2.md) · [F3: recursos](F3.md) · [F4: planificación](F4.md) · [F5: inventario](F5.md) · [F6: deadlock](F6.md) · [F7: métricas](F7.md) · [F8: API](F8.md) · [F9: interfaz](F9.md). Estos documentos describen el alcance de su fase; para el estado final prevalecen este índice y el [informe de verificación](../INFORME_VERIFICACION_FINAL.md).
