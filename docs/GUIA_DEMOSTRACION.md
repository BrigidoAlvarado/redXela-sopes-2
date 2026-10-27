# Guía de demostración

## Preparación

Con Java 21 y Node 24, abra dos terminales: `cd backend && ./mvnw spring-boot:run` y `cd frontend && npm ci && npm start`. Abra http://127.0.0.1:4200. Inicie el motor y compruebe conexión en tiempo real. El inventario comienza vacío al reiniciar el backend.

## Recorrido

1. **Pedido normal:** registre un cliente, reciba GEN-01 y espere `ALMACENADA`; cree un pedido con ese producto. Observe el avance por etapas, el descuento de stock, la liberación de recursos y los contadores en el panel.
2. **Sin inventario:** cree un pedido de un producto agotado. Muestre `ESPERANDO_INVENTARIO` y registre una recepción suficiente. Observe la reactivación y finalización. Una reserva completa evita que dos pedidos consuman las mismas unidades.
3. **Carga y políticas:** en Pedidos configure el generador y actívelo. Observe pedidos de distintos niveles; pause el motor y muestre que el reloj y el generador dejan de avanzar. Reanude y detenga el generador. En Planificación cambie entre PRIORIDAD, AGING y SJF y consulte la cola.
4. **Recursos:** abra Recursos y muestre identificadores, propietarios y disponibilidad, incluido S01. Las tarjetas reflejan permisos del backend.
5. **Deadlock:** cree dos pedidos exclusivamente GEN-01 sin stock; selecciónelos en Deadlock antes de recibir mercancía. Registre dos unidades y espere la detección. Muestre A con C01 esperando S01 y B con S01 esperando C01. Seleccione A o B, espere `RESUELTA` y observe que ambos pedidos siguen su flujo. Repita con dos pedidos nuevos y el otro ganador.
6. **Resultados:** consulte métricas y ejecute la comparación de políticas, por ejemplo 60 segundos simulados con factor 100. Explique que la carga es equivalente, pero el orden de los hilos y los resultados pueden variar. Detenga la simulación.

## Ideas para explicar

- Dos hilos pueden leer el mismo stock antes de descontarlo: eso causa una condición de carrera. La reserva completa bajo exclusión mutua impide asignar dos veces las unidades.
- Cada semáforo representa disponibilidad real; un permiso permite usar una unidad física. Las secciones críticas coordinan propietario y existencias.
- Recepción produce mensajes en una `BlockingQueue`; Inventario los consume independientemente dentro de la misma JVM.
- En el conflicto, cada despacho retiene un recurso y espera el del otro. Se satisfacen las cuatro condiciones de Coffman. La detección consulta asignaciones y esperas, no una marca visual.
- El usuario elige un ganador. El perdedor coopera liberando sus permisos y vuelve a esperar; el ganador adquiere el recurso restante y termina. No se repite el retiro de inventario.
- Prioridad favorece el nivel contratado; aging incrementa la preferencia con el tiempo esperando; SJF favorece menos unidades. Los objetivos de servicio miden primera atención, no toda la espera acumulada.
