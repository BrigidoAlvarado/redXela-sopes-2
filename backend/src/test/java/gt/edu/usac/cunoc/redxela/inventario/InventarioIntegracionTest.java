package gt.edu.usac.cunoc.redxela.inventario;

import gt.edu.usac.cunoc.redxela.catalogo.*;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.pedidos.*;
import gt.edu.usac.cunoc.redxela.recepcion.RecepcionService;
import gt.edu.usac.cunoc.redxela.recursos.*;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class InventarioIntegracionTest {
    private final CatalogoProductos catalogo = new CatalogoProductos();
    private final InventarioService inventario = new InventarioService(catalogo);
    private final ClienteService clientes = new ClienteService();
    private final PedidoService pedidos = new PedidoService(catalogo, clientes);
    private final GestorRecursos recursos = new GestorRecursos();

    private Pedido pedido(SolicitudLineaPedido... lineas) {
        var cliente = clientes.registrar("Cliente", UUID.randomUUID() + "@ejemplo.com", NivelServicio.EXPRES);
        return pedidos.crear(cliente.id(), NivelServicio.EXPRES, List.of(lineas));
    }
    private SolicitudLineaPedido linea(String codigo, int cantidad) { return new SolicitudLineaPedido(codigo, cantidad); }
    private void ingreso(String codigo, int cantidad) { assertTrue(inventario.almacenar(UUID.randomUUID(), codigo, cantidad)); }

    @Test void reservaCompletaYRetiroNoRepetido() {
        ingreso("GEN-01", 5);
        var mixto = pedido(linea("GEN-01", 5), linea("FRA-01", 1));
        assertFalse(inventario.reservar(mixto));
        assertEquals(5, inventario.existencia("GEN-01").disponible());
        ingreso("FRA-01", 1);
        assertTrue(inventario.reservar(mixto));
        assertEquals(5, inventario.existencia("GEN-01").fisico());
        assertEquals(5, inventario.existencia("GEN-01").reservado());
        assertEquals(0, inventario.existencia("GEN-01").disponible());
        inventario.retirar(mixto.id(), TipoMercancia.GENERAL);
        assertEquals(0, inventario.existencia("GEN-01").fisico());
        assertThrows(IllegalStateException.class, () -> inventario.retirar(mixto.id(), TipoMercancia.GENERAL));
        inventario.cancelarReserva(mixto.id());
        assertEquals(1, inventario.existencia("FRA-01").disponible());
        assertEquals(2, inventario.ubicaciones().stream().mapToInt(InventarioService.EstadoUbicacion::ocupado).sum());
    }

    @Test void dosPedidosNoReservanLasMismasUnidades() throws Exception {
        ingreso("GEN-01", 5);
        var a = pedido(linea("GEN-01", 5)); var b = pedido(linea("GEN-01", 5));
        var inicio = new CountDownLatch(1);
        try (var ejecutor = Executors.newFixedThreadPool(2)) {
            var primero = ejecutor.submit(() -> { inicio.await(); return inventario.reservar(a); });
            var segundo = ejecutor.submit(() -> { inicio.await(); return inventario.reservar(b); });
            inicio.countDown();
            assertNotEquals(primero.get(5, TimeUnit.SECONDS), segundo.get(5, TimeUnit.SECONDS));
            assertEquals(0, inventario.existencia("GEN-01").disponible());
            assertEquals(5, inventario.existencia("GEN-01").reservado());
        }
    }

    @Test void loteFragmentadoEntreHuecosNoContiguosSinExcederCapacidad() {
        for (int i = 0; i < 50; i++) { ingreso("GEN-01", 10); ingreso("FRA-01", 5); }
        assertFalse(inventario.almacenar(UUID.randomUUID(), "GEN-02", 1));
        var pedido = pedido(linea("GEN-01", 500));
        assertTrue(inventario.reservar(pedido));
        inventario.retirar(pedido.id(), TipoMercancia.GENERAL);
        UUID lote = UUID.randomUUID();
        assertTrue(inventario.almacenar(lote, "GEN-02", 20));
        var posiciones = inventario.ubicaciones().stream()
                .filter(u -> u.fragmentos().stream().anyMatch(f -> f.lote().equals(lote)))
                .map(InventarioService.EstadoUbicacion::id).toList();
        assertEquals(List.of("P01-E01-N01", "P01-E01-N03"), posiciones);
        assertTrue(inventario.ubicaciones().stream().allMatch(u -> u.ocupado() <= u.capacidad()));
        assertThrows(IllegalArgumentException.class, () -> inventario.almacenar(UUID.randomUUID(), "PES-01", 301));
    }

    @Test void esperaInventarioSeReactivaPorRecepcionSinDobleRetiro() throws Exception {
        var avisos = new Semaphore(0);
        inventario.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(3, 1_000_000);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 10)) {
            recepcion.registrarAvisos(avisos);
            motor.iniciar();
            var p = pedido(linea("GEN-01", 3));
            var futuro = procesador.procesar(p.id());
            assertEquals(EstadoPedido.ESPERANDO_INVENTARIO, procesador.consultar(p.id()).estado());
            assertEquals("Inventario insuficiente", procesador.consultarCola().getFirst().motivo());
            var id = recepcion.registrar("GEN-01", 5);
            esperar(avisos, () -> recepcion.consultar(id).estado() == RecepcionService.Estado.ALMACENADA);
            assertEquals(EstadoPedido.COMPLETADO, futuro.get(10, TimeUnit.SECONDS).estado());
            assertEquals(2, inventario.existencia("GEN-01").fisico());
            assertEquals(0, inventario.existencia("GEN-01").reservado());
        } finally { inventario.retirarAvisos(avisos); }
    }

    @Test void colaAcotadaYContencionDelUnicoEscaner() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(2, 1_000_000);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 1);
             var despacho = recursos.intentarAsignar("despacho", new RequisitosRecursos(Map.of(TipoRecurso.ESCANER, 1))).orElseThrow()) {
            recepcion.registrarAvisos(avisos);
            motor.iniciar();
            var id = recepcion.registrar("GEN-01", 2);
            esperar(avisos, () -> recursos.esperando().containsKey("recepcion:" + id)
                    && recepcion.consultar(id).estado() == RecepcionService.Estado.ESPERANDO_ESCANER);
            assertEquals("S01", despacho.recursos().getFirst());
            assertEquals(RecepcionService.Estado.ESPERANDO_ESCANER, recepcion.consultar(id).estado());
            assertThrows(RejectedExecutionException.class, () -> recepcion.registrar("GEN-02", 1));
            despacho.close();
            esperar(avisos, () -> recepcion.consultar(id).estado() == RecepcionService.Estado.ALMACENADA);
            assertEquals(2, inventario.existencia("GEN-01").fisico());
            assertEquals(1, recursos.disponibles(TipoRecurso.ESCANER));
        } finally { recursos.retirarAvisos(avisos); }
    }

    @Test void recepcionYDespachoRealEsperanElMismoEscaner() throws Exception {
        ingreso("GEN-01", 1);
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(2, 1_000_000);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 4);
             var bloqueo = recursos.intentarAsignar("bloqueo", new RequisitosRecursos(Map.of(TipoRecurso.ESCANER, 1))).orElseThrow()) {
            recepcion.registrarAvisos(avisos); motor.iniciar();
            var p = pedido(linea("GEN-01", 1));
            var pedidoTerminado = procesador.procesar(p.id());
            esperar(avisos, () -> recursos.esperando().containsKey(p.id() + ":2"));
            assertEquals(EtapaPedido.DESPACHO, procesador.consultar(p.id()).etapa());
            var id = recepcion.registrar("GEN-02", 1);
            esperar(avisos, () -> recursos.esperando().containsKey("recepcion:" + id));
            assertEquals(0, recursos.disponibles(TipoRecurso.ESCANER));
            bloqueo.close();
            assertEquals(EstadoPedido.COMPLETADO, pedidoTerminado.get(10, TimeUnit.SECONDS).estado());
            esperar(avisos, () -> recepcion.consultar(id).estado() == RecepcionService.Estado.ALMACENADA);
            assertEquals(1, recursos.disponibles(TipoRecurso.ESCANER));
        } finally { recursos.retirarAvisos(avisos); }
    }

    @Test void pendientePorEspacioNoBloqueaOtrosYSeReevaluaAlRetirar() throws Exception {
        ingreso("GEN-01", 990);
        var avisos = new Semaphore(0);
        try (var motor = new MotorSimulacion(2, 1_000_000);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 10)) {
            recepcion.registrarAvisos(avisos); motor.iniciar();
            var grande = recepcion.registrar("GEN-02", 20);
            esperar(avisos, () -> recepcion.consultar(grande).estado() == RecepcionService.Estado.PENDIENTE_ESPACIO
                    && recepcion.consultar(grande).detalle() != null);
            var pequeno = recepcion.registrar("GEN-02", 5);
            esperar(avisos, () -> recepcion.consultar(pequeno).estado() == RecepcionService.Estado.ALMACENADA);
            var p = pedido(linea("GEN-01", 20));
            assertTrue(inventario.reservar(p));
            inventario.retirar(p.id(), TipoMercancia.GENERAL);
            esperar(avisos, () -> recepcion.consultar(grande).estado() == RecepcionService.Estado.ALMACENADA);
            assertEquals(25, inventario.existencia("GEN-02").fisico());
        }
    }

    @Test void recepcionesAceptadasSeProcesanSinPerdida() throws Exception {
        var avisos = new Semaphore(0);
        try (var motor = new MotorSimulacion(3, 1_000_000);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 20)) {
            recepcion.registrarAvisos(avisos); motor.iniciar();
            var ids = new ArrayList<UUID>();
            for (int i = 0; i < 20; i++) ids.add(recepcion.registrar("GEN-01", 1));
            esperar(avisos, () -> ids.stream().allMatch(id -> recepcion.consultar(id).estado() == RecepcionService.Estado.ALMACENADA));
            assertEquals(20, inventario.existencia("GEN-01").fisico());
            assertEquals(20, inventario.ubicaciones().stream().flatMap(u -> u.fragmentos().stream()).count());
            motor.detener(); // Espera el fin físico de los trabajadores antes de inspeccionar el registro.
            var campo = RecepcionService.class.getDeclaredField("trabajos");
            campo.setAccessible(true);
            synchronized (recepcion) { assertTrue(((Map<?, ?>) campo.get(recepcion)).isEmpty()); }
        }
    }

    @Test void detenerLiberaReservasYConservaElRetiroConfirmado() throws Exception {
        ingreso("GEN-01", 5);
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(1, 1_000_000);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var bloqueo = recursos.intentarAsignar("bloqueo-empaque", new RequisitosRecursos(Map.of(TipoRecurso.ESTACION_EMPAQUE, 6))).orElseThrow()) {
            motor.iniciar();
            var p = pedido(linea("GEN-01", 3));
            var resultado = procesador.procesar(p.id());
            esperar(avisos, () -> recursos.esperando().containsKey(p.id() + ":1"));
            assertEquals(1, procesador.consultar(p.id()).pasosCompletados());
            assertEquals(2, inventario.existencia("GEN-01").fisico());
            motor.detener();
            assertThrows(ExecutionException.class, () -> resultado.get(5, TimeUnit.SECONDS));
            assertEquals(2, inventario.existencia("GEN-01").fisico());
            assertEquals(0, inventario.existencia("GEN-01").reservado());
        } finally { recursos.retirarAvisos(avisos); }
    }

    @Test void detenerConservaRecepcionQueEsperaEscanerParaLaSiguienteCorrida() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(1, 60);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 2);
             var ocupado = recursos.intentarAsignar("despacho", new RequisitosRecursos(Map.of(TipoRecurso.ESCANER, 1))).orElseThrow()) {
            recepcion.registrarAvisos(avisos); motor.iniciar();
            var id = recepcion.registrar("GEN-01", 1);
            esperar(avisos, () -> recursos.esperando().containsKey("recepcion:" + id));
            motor.detener();
            esperar(avisos, () -> recepcion.consultar(id).detalle() != null);
            assertFalse(recursos.esperando().containsKey("recepcion:" + id));
            assertEquals(0, inventario.existencia("GEN-01").fisico());
            ocupado.close();
            motor.cambiarVelocidad(1_000_000);
            motor.iniciar();
            esperar(avisos, () -> recepcion.consultar(id).estado() == RecepcionService.Estado.ALMACENADA);
            assertEquals(1, inventario.existencia("GEN-01").fisico());
        } finally { recursos.retirarAvisos(avisos); }
    }

    @Test void cerrarRecepcionInterrumpeEscaneoYLiberaEscaner() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(1, 0.01)) {
            var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 2);
            recepcion.registrarAvisos(avisos); motor.iniciar();
            var id = recepcion.registrar("GEN-01", 1);
            esperar(avisos, () -> recursos.estado().stream().anyMatch(u -> ("recepcion:" + id).equals(u.propietario())));
            recepcion.close();
            esperar(avisos, () -> recursos.disponibles(TipoRecurso.ESCANER) == 1);
            assertEquals(RecepcionService.Estado.ERROR, recepcion.consultar(id).estado());
            assertEquals(0, inventario.existencia("GEN-01").fisico());
        } finally { recursos.retirarAvisos(avisos); }
    }

    @Test void pausaNoAlmacenaPendientesAunqueSeLibereEspacio() throws Exception {
        ingreso("GEN-01", 1000);
        var avisos = new Semaphore(0);
        try (var motor = new MotorSimulacion(2, 1_000_000);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 2)) {
            recepcion.registrarAvisos(avisos); motor.iniciar();
            var id = recepcion.registrar("GEN-02", 5);
            esperar(avisos, () -> recepcion.consultar(id).estado() == RecepcionService.Estado.PENDIENTE_ESPACIO
                    && recepcion.consultar(id).detalle() != null);
            motor.pausar();
            var pedido = pedido(linea("GEN-01", 5));
            assertTrue(inventario.reservar(pedido));
            inventario.retirar(pedido.id(), TipoMercancia.GENERAL);
            assertFalse(motor.avanzarSiEjecutando(() -> fail("Se avanzó durante la pausa")));
            assertEquals(RecepcionService.Estado.PENDIENTE_ESPACIO, recepcion.consultar(id).estado());
            assertEquals(0, inventario.existencia("GEN-02").fisico());
            motor.reanudar();
            esperar(avisos, () -> recepcion.consultar(id).estado() == RecepcionService.Estado.ALMACENADA);
            assertEquals(5, inventario.existencia("GEN-02").fisico());
        }
    }

    @Test void detenerMientrasSeEscaneaReintentaSinDuplicar() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(1, 0.01);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 2)) {
            recepcion.registrarAvisos(avisos); motor.iniciar();
            var id = recepcion.registrar("GEN-01", 3);
            esperar(avisos, () -> recursos.estado().stream().anyMatch(u -> ("recepcion:" + id).equals(u.propietario())));
            motor.detener();
            assertEquals(0, inventario.existencia("GEN-01").fisico());
            assertEquals(1, recursos.disponibles(TipoRecurso.ESCANER));
            motor.cambiarVelocidad(1_000_000); motor.iniciar();
            esperar(avisos, () -> recepcion.consultar(id).estado() == RecepcionService.Estado.ALMACENADA);
            assertEquals(3, cantidadLote(id));
            assertEquals(3, inventario.existencia("GEN-01").fisico());
        } finally { recursos.retirarAvisos(avisos); }
    }

    @Test void detenerDespuesDePublicarConservaMensajeSinDuplicar() throws Exception {
        ingreso("GEN-01", 1000);
        var avisos = new Semaphore(0);
        try (var motor = new MotorSimulacion(2, 1_000_000);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 2)) {
            recepcion.registrarAvisos(avisos); motor.iniciar();
            var id = recepcion.registrar("GEN-02", 5);
            esperar(avisos, () -> recepcion.consultar(id).estado() == RecepcionService.Estado.PENDIENTE_ESPACIO
                    && recepcion.consultar(id).detalle() != null);
            motor.detener();
            var pedido = pedido(linea("GEN-01", 5));
            assertTrue(inventario.reservar(pedido));
            inventario.retirar(pedido.id(), TipoMercancia.GENERAL);
            assertEquals(0, inventario.existencia("GEN-02").fisico());
            assertEquals(RecepcionService.Estado.PENDIENTE_ESPACIO, recepcion.consultar(id).estado());
            motor.iniciar();
            esperar(avisos, () -> recepcion.consultar(id).estado() == RecepcionService.Estado.ALMACENADA);
            assertEquals(5, cantidadLote(id));
            assertEquals(5, inventario.existencia("GEN-02").fisico());
        }
    }

    @Test void detenerDurantePublicacionNoDuplicaElMensaje() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(1, 0.01);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 2);
             var controlador = Executors.newSingleThreadExecutor()) {
            recepcion.registrarAvisos(avisos); motor.iniciar();
            var id = recepcion.registrar("GEN-01", 4);
            esperar(avisos, () -> recursos.estado().stream().anyMatch(u -> ("recepcion:" + id).equals(u.propietario())));
            Future<?> detener;
            synchronized (recepcion) {
                motor.cambiarVelocidad(1_000_000);
                // El trabajador queda a la entrada de la publicación, dentro de la puerta del motor.
                esperarBloqueoDePublicacion(recepcion);
                var inicioDetencion = new CountDownLatch(1);
                detener = controlador.submit(() -> { inicioDetencion.countDown(); motor.detener(); return null; });
                assertTrue(inicioDetencion.await(5, TimeUnit.SECONDS));
            }
            detener.get(10, TimeUnit.SECONDS);
            motor.iniciar();
            esperar(avisos, () -> recepcion.consultar(id).estado() == RecepcionService.Estado.ALMACENADA);
            assertEquals(4, cantidadLote(id));
            assertEquals(4, inventario.existencia("GEN-01").fisico());
        } finally { recursos.retirarAvisos(avisos); }
    }

    private static void esperarBloqueoDePublicacion(RecepcionService recepcion) {
        var gestor = java.lang.management.ManagementFactory.getThreadMXBean();
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < limite) {
            for (var info : gestor.dumpAllThreads(false, false)) {
                if (info.getThreadName().startsWith("redxela-corrida-")
                        && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                        && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(recepcion)) return;
            }
            Thread.yield();
        }
        fail("El trabajador no llegó al punto de publicación");
    }

    private int cantidadLote(UUID id) {
        return inventario.ubicaciones().stream().flatMap(u -> u.fragmentos().stream())
                .filter(f -> f.lote().equals(id)).mapToInt(InventarioService.Fragmento::cantidad).sum();
    }

    private static void esperar(Semaphore avisos, BooleanSupplier condicion) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condicion.getAsBoolean()) {
            long restante = limite - System.nanoTime();
            assertTrue(restante > 0 && avisos.tryAcquire(restante, TimeUnit.NANOSECONDS), "No llegó la señal esperada");
        }
    }
}
