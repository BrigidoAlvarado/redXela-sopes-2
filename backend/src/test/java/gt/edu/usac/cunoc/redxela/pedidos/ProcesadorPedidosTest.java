package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.inventario.InventarioService;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.recursos.RequisitosRecursos;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static gt.edu.usac.cunoc.redxela.recursos.TipoRecurso.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcesadorPedidosTest {
    private final CatalogoProductos catalogo = new CatalogoProductos();
    private final ClienteService clientes = new ClienteService();
    private final PedidoService pedidos = new PedidoService(catalogo, clientes);
    private final GestorRecursos recursos = new GestorRecursos();
    private final InventarioService inventario = inventarioInicial();

    private static InventarioService inventarioInicial() {
        var inventario = new InventarioService(new CatalogoProductos());
        inventario.almacenar(java.util.UUID.randomUUID(), "GEN-01", 100);
        inventario.almacenar(java.util.UUID.randomUUID(), "FRA-01", 100);
        inventario.almacenar(java.util.UUID.randomUUID(), "PES-01", 100);
        return inventario;
    }

    private Pedido pedido(String producto, int unidades) {
        var cliente = clientes.registrar("Cliente", java.util.UUID.randomUUID() + "@ejemplo.com", NivelServicio.EXPRES);
        return pedidos.crear(cliente.id(), NivelServicio.EXPRES, List.of(new SolicitudLineaPedido(producto, unidades)));
    }

    @RepeatedTest(10) void pedidosSeProcesanEnParaleloYDetenerLiberaRecursos() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(2, 0.01);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
            var a = pedido("GEN-01", 1);
            var b = pedido("FRA-01", 1);
            motor.iniciar();
            var fa = procesador.procesar(a.id());
            var fb = procesador.procesar(b.id());
            esperar(avisos, () -> recursos.estado().stream().filter(u -> u.propietario() != null
                    && (u.propietario().startsWith(a.id().toString()) || u.propietario().startsWith(b.id().toString())))
                    .map(GestorRecursos.EstadoUnidad::propietario).distinct().count() == 2);
            motor.pausar();
            var ra = procesador.consultar(a.id()).recursos();
            var rb = procesador.consultar(b.id()).recursos();
            assertFalse(ra.isEmpty());
            assertTrue(ra.stream().noneMatch(rb::contains));
            motor.detener();
            assertThrows(ExecutionException.class, () -> fa.get(10, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> fb.get(10, TimeUnit.SECONDS));
            for (var tipo : gt.edu.usac.cunoc.redxela.recursos.TipoRecurso.values())
                assertEquals(tipo.capacidad(), recursos.disponibles(tipo));
        } finally { recursos.retirarAvisos(avisos); }
    }

    @RepeatedTest(10) void esperaPorRecursosSinRetenerTrabajadorYContinuaAlLiberarlos() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(1, 1_000_000);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var bloqueo = recursos.intentarAsignar("prueba", new RequisitosRecursos(Map.of(ENCARGADO_BODEGA, 10))).orElseThrow()) {
            var p = pedido("GEN-01", 3);
            motor.iniciar();
            motor.registrarAvisos(avisos);
            var resultado = procesador.procesar(p.id());
            esperar(avisos, () -> recursos.esperando().keySet().stream().anyMatch(s -> s.startsWith(p.id().toString())));
            assertEquals(EstadoPedido.ESPERANDO_RECURSOS, procesador.consultar(p.id()).estado());
            assertEquals(6, recursos.disponibles(ESTACION_EMPAQUE));
            java.util.concurrent.Future<Void> comprobacion;
            while (true) {
                try {
                    comprobacion = motor.enviarTrabajo(contexto -> { });
                    break;
                } catch (java.util.concurrent.RejectedExecutionException aunTerminando) {
                    assertTrue(avisos.tryAcquire(10, TimeUnit.SECONDS));
                }
            }
            comprobacion.get(10, TimeUnit.SECONDS);
            bloqueo.close();
            var terminado = resultado.get(10, TimeUnit.SECONDS);
            assertEquals(EstadoPedido.COMPLETADO, terminado.estado());
            assertEquals(terminado.pasosTotales(), terminado.pasosCompletados());
            assertEquals(100, terminado.porcentaje());
            for (var tipo : gt.edu.usac.cunoc.redxela.recursos.TipoRecurso.values())
                assertEquals(tipo.capacidad(), recursos.disponibles(tipo));
            assertThrows(IllegalStateException.class, () -> procesador.procesar(p.id()));
        } finally { recursos.retirarAvisos(avisos); }
    }

    @Test void pausaCongelaElPedidoYAlReanudarTermina() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(1, 0.01);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
            var p = pedido("GEN-01", 1);
            motor.iniciar();
            var resultado = procesador.procesar(p.id());
            esperar(avisos, () -> recursos.estado().stream().anyMatch(u -> u.propietario() != null
                    && u.propietario().startsWith(p.id().toString())));
            motor.pausar();
            var tiempo = motor.tiempoActual();
            var progreso = procesador.consultar(p.id());
            assertEquals(tiempo, motor.tiempoActual());
            assertEquals(0, progreso.pasosCompletados());
            assertNotNull(progreso.restanteEstimado());
            assertEquals(progreso.restanteEstimado(), procesador.consultar(p.id()).restanteEstimado());
            assertFalse(resultado.isDone());
            motor.cambiarVelocidad(1_000_000);
            motor.reanudar();
            assertEquals(EstadoPedido.COMPLETADO, resultado.get(10, TimeUnit.SECONDS).estado());
        } finally { recursos.retirarAvisos(avisos); }
    }

    @Test void duracionDependeDeUnidades() {
        var corto = PlanTrabajo.paraPedido(pedido("GEN-01", 1)).pasos().getFirst();
        var largo = PlanTrabajo.paraPedido(pedido("GEN-01", 10)).pasos().getFirst();
        assertEquals(Duration.ofMillis(1100), ProcesadorPedidos.duracion(corto));
        assertEquals(Duration.ofSeconds(2), ProcesadorPedidos.duracion(largo));
    }

    @Test void detenerCancelaTambienPedidosQueEsperanRecursos() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        try (var motor = new MotorSimulacion(1, 60);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var bloqueo = recursos.intentarAsignar("prueba", new RequisitosRecursos(Map.of(ENCARGADO_BODEGA, 10))).orElseThrow()) {
            var p = pedido("GEN-01", 1);
            motor.iniciar();
            var resultado = procesador.procesar(p.id());
            esperar(avisos, () -> recursos.esperando().keySet().stream().anyMatch(s -> s.startsWith(p.id().toString())));
            motor.detener();
            assertThrows(ExecutionException.class, () -> resultado.get(10, TimeUnit.SECONDS));
            assertEquals(EstadoPedido.ERROR, procesador.consultar(p.id()).estado());
            assertFalse(recursos.esperando().keySet().stream().anyMatch(s -> s.startsWith(p.id().toString())));
        } finally { recursos.retirarAvisos(avisos); }
    }

    private static void esperar(Semaphore avisos, BooleanSupplier condicion) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condicion.getAsBoolean()) {
            long restante = limite - System.nanoTime();
            assertTrue(restante > 0 && avisos.tryAcquire(restante, TimeUnit.NANOSECONDS), "No llegó la señal esperada");
        }
    }
}
