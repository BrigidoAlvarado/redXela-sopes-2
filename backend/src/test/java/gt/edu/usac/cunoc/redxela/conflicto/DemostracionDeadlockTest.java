package gt.edu.usac.cunoc.redxela.conflicto;

import gt.edu.usac.cunoc.redxela.catalogo.*;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.inventario.InventarioService;
import gt.edu.usac.cunoc.redxela.pedidos.*;
import gt.edu.usac.cunoc.redxela.recursos.*;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class DemostracionDeadlockTest {
    private final CatalogoProductos catalogo = new CatalogoProductos();
    private final ClienteService clientes = new ClienteService();
    private final PedidoService pedidos = new PedidoService(catalogo, clientes);
    private final InventarioService inventario = new InventarioService(catalogo);
    private final GestorRecursos recursos = new GestorRecursos();
    private final Semaphore aviso = new Semaphore(0);

    private Pedido pedido() {
        var cliente = clientes.registrar("Cliente", UUID.randomUUID() + "@ejemplo.com", NivelServicio.EXPRES);
        return pedidos.crear(cliente.id(), NivelServicio.EXPRES, List.of(new SolicitudLineaPedido("GEN-01", 1)));
    }

    @Test void recuperarSeleccionandoA() throws Exception { recuperar(true); }
    @Test void recuperarSeleccionandoB() throws Exception { recuperar(false); }

    private void recuperar(boolean elegirA) throws Exception {
        recursos.registrarAvisos(aviso);
        try (var motor = new MotorSimulacion(2, 1_000_000);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var demostracion = new DemostracionDeadlock(motor, procesador, recursos)) {
            procesador.registrarAvisos(aviso); motor.iniciar();
            var a = pedido(); var b = pedido();
            var fa = procesador.procesar(a.id()); var fb = procesador.procesar(b.id());
            demostracion.iniciar(a.id(), b.id());
            assertTrue(inventario.almacenar(UUID.randomUUID(), "GEN-01", 2));
            esperar(() -> demostracion.consultar().deadlock());
            var vista = demostracion.consultar();
            assertEquals(List.of("C01"), vista.a().posee()); assertEquals("S01", vista.a().espera());
            assertEquals(List.of("S01"), vista.b().posee()); assertEquals("C01", vista.b().espera());
            assertFalse(fa.isDone()); assertFalse(fb.isDone());
            assertEquals(2, procesador.consultar(a.id()).pasosCompletados());
            assertEquals(2, procesador.consultar(b.id()).pasosCompletados());
            assertEquals(0, inventario.existencia("GEN-01").fisico());
            motor.pausar();
            assertTrue(demostracion.consultar().deadlock());
            assertThrows(IllegalStateException.class, () -> demostracion.resolver(a.id()));
            motor.reanudar();
            motor.cambiarVelocidad(0.01);
            demostracion.resolver(elegirA ? a.id() : b.id());
            var victima = elegirA ? b : a;
            var futuroVictima = elegirA ? fb : fa;
            esperar(() -> procesador.consultar(victima.id()).estado() == EstadoPedido.ESPERANDO_RECURSOS);
            assertFalse(futuroVictima.isDone());
            assertEquals(2, procesador.consultar(victima.id()).pasosCompletados());
            motor.cambiarVelocidad(1_000_000);
            assertEquals(EstadoPedido.COMPLETADO, fa.get(10, TimeUnit.SECONDS).estado());
            assertEquals(EstadoPedido.COMPLETADO, fb.get(10, TimeUnit.SECONDS).estado());
            assertEquals(0, inventario.existencia("GEN-01").fisico());
            assertEquals(0, inventario.existencia("GEN-01").reservado());
            assertEquals(elegirA ? a.id() : b.id(), demostracion.consultar().ganador());
            assertThrows(IllegalStateException.class, () -> demostracion.resolver(a.id()));
            esperar(() -> java.util.Arrays.stream(TipoRecurso.values()).allMatch(tipo -> tipo.capacidad() == recursos.disponibles(tipo)));
        } finally { recursos.retirarAvisos(aviso); }
    }

    @Test void detenerDuranteDeadlockLiberaPermisosYPermiteReiniciar() throws Exception {
        recursos.registrarAvisos(aviso);
        try (var motor = new MotorSimulacion(2, 1_000_000);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var demostracion = new DemostracionDeadlock(motor, procesador, recursos)) {
            procesador.registrarAvisos(aviso); motor.iniciar();
            var a = pedido(); var b = pedido();
            var fa = procesador.procesar(a.id()); var fb = procesador.procesar(b.id());
            demostracion.iniciar(a.id(), b.id());
            assertTrue(inventario.almacenar(UUID.randomUUID(), "GEN-01", 2));
            esperar(() -> demostracion.consultar().deadlock());
            motor.detener();
            assertThrows(ExecutionException.class, () -> fa.get(10, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> fb.get(10, TimeUnit.SECONDS));
            for (var tipo : TipoRecurso.values()) assertEquals(tipo.capacidad(), recursos.disponibles(tipo));
            motor.iniciar();
            var c = pedido(); var d = pedido();
            var fc = procesador.procesar(c.id()); var fd = procesador.procesar(d.id());
            demostracion.iniciar(c.id(), d.id());
            assertTrue(inventario.almacenar(UUID.randomUUID(), "GEN-01", 2));
            esperar(() -> demostracion.consultar().deadlock());
            demostracion.resolver(c.id());
            assertEquals(EstadoPedido.COMPLETADO, fc.get(10, TimeUnit.SECONDS).estado());
            assertEquals(EstadoPedido.COMPLETADO, fd.get(10, TimeUnit.SECONDS).estado());
        } finally { recursos.retirarAvisos(aviso); }
    }

    @Test void detenerDuranteRecuperacionInterrumpeAmbasTareasSinRetenerRecursos() throws Exception {
        recursos.registrarAvisos(aviso);
        try (var motor = new MotorSimulacion(2, 1_000_000);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var demostracion = new DemostracionDeadlock(motor, procesador, recursos)) {
            procesador.registrarAvisos(aviso); motor.iniciar();
            var a = pedido(); var b = pedido();
            var fa = procesador.procesar(a.id()); var fb = procesador.procesar(b.id());
            demostracion.iniciar(a.id(), b.id());
            assertTrue(inventario.almacenar(UUID.randomUUID(), "GEN-01", 2));
            esperar(() -> demostracion.consultar().deadlock());
            motor.cambiarVelocidad(0.01);
            demostracion.resolver(a.id());
            esperar(() -> procesador.consultar(b.id()).estado() == EstadoPedido.ESPERANDO_RECURSOS);
            assertEquals(DemostracionDeadlock.Estado.RECUPERANDO, demostracion.consultar().estado());
            motor.detener();
            assertThrows(ExecutionException.class, () -> fa.get(10, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> fb.get(10, TimeUnit.SECONDS));
            esperar(() -> java.util.Arrays.stream(TipoRecurso.values())
                    .allMatch(tipo -> tipo.capacidad() == recursos.disponibles(tipo)));
        } finally { recursos.retirarAvisos(aviso); }
    }

    @Test void cancelarDemostracionDuranteRecuperacionInterrumpeSinDejarPermisos() throws Exception {
        recursos.registrarAvisos(aviso);
        try (var motor = new MotorSimulacion(2, 1_000_000);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var demostracion = new DemostracionDeadlock(motor, procesador, recursos)) {
            procesador.registrarAvisos(aviso); motor.iniciar();
            var a = pedido(); var b = pedido();
            var fa = procesador.procesar(a.id()); var fb = procesador.procesar(b.id());
            demostracion.iniciar(a.id(), b.id());
            assertTrue(inventario.almacenar(UUID.randomUUID(), "GEN-01", 2));
            esperar(() -> demostracion.consultar().deadlock());
            motor.cambiarVelocidad(0.01);
            demostracion.resolver(b.id());
            esperar(() -> procesador.consultar(a.id()).estado() == EstadoPedido.ESPERANDO_RECURSOS);
            demostracion.close();
            assertThrows(ExecutionException.class, () -> fa.get(10, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> fb.get(10, TimeUnit.SECONDS));
            esperar(() -> java.util.Arrays.stream(TipoRecurso.values())
                    .allMatch(tipo -> tipo.capacidad() == recursos.disponibles(tipo)));
        } finally { recursos.retirarAvisos(aviso); }
    }

    private void esperar(BooleanSupplier condicion) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condicion.getAsBoolean()) {
            long restante = limite - System.nanoTime();
            assertTrue(restante > 0 && aviso.tryAcquire(restante, TimeUnit.NANOSECONDS), "No llegó la señal esperada");
        }
    }
}
