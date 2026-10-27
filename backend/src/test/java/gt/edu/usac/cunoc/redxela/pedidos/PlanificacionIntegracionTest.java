package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.inventario.InventarioService;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.recursos.RequisitosRecursos;
import gt.edu.usac.cunoc.redxela.simulacion.MotorControlado;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static gt.edu.usac.cunoc.redxela.catalogo.NivelServicio.*;
import static gt.edu.usac.cunoc.redxela.planificacion.PoliticaPlanificacion.*;
import static gt.edu.usac.cunoc.redxela.recursos.TipoRecurso.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanificacionIntegracionTest {
    private final ClienteService clientes = new ClienteService();
    private final PedidoService pedidos = new PedidoService(new CatalogoProductos(), clientes);
    private final GestorRecursos recursos = new GestorRecursos();
    private final InventarioService inventario = inventarioInicial();

    private static InventarioService inventarioInicial() {
        var inventario = new InventarioService(new CatalogoProductos());
        inventario.almacenar(java.util.UUID.randomUUID(), "GEN-01", 100);
        inventario.almacenar(java.util.UUID.randomUUID(), "FRA-01", 100);
        inventario.almacenar(java.util.UUID.randomUUID(), "PES-01", 100);
        return inventario;
    }

    private Pedido pedido(NivelServicio nivel, int unidades, String producto) {
        var cliente = clientes.registrar("Cliente", UUID.randomUUID() + "@ejemplo.com", nivel);
        return pedidos.crear(cliente.id(), nivel, List.of(new SolicitudLineaPedido(producto, unidades)));
    }

    @Test void relojSimuladoAgingYPausaControlanLaColaSinIncrementarTiempoReal() throws Exception {
        var control = new MotorControlado();
        var motor = control.motor();
        var ocupado = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        try (motor; var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
            motor.iniciar();
            motor.enviarTrabajo(contexto -> { ocupado.countDown(); liberar.await(); });
            assertTrue(ocupado.await(10, TimeUnit.SECONDS));
            var antiguo = pedido(ECONOMICO, 20, "GEN-01");
            procesador.procesar(antiguo.id());
            control.avanzarReal(Duration.ofSeconds(25)); // 25 minutos simulados.
            var nuevo = pedido(EXPRES, 5, "GEN-01");
            procesador.procesar(nuevo.id());

            assertEquals(List.of(nuevo.id(), antiguo.id()), ids(procesador));
            procesador.cambiarPolitica(AGING);
            assertEquals(List.of(antiguo.id(), nuevo.id()), ids(procesador));
            var entrada = procesador.consultarCola().getFirst();
            assertEquals(Duration.ofMinutes(25), entrada.esperaAcumulada());
            assertEquals(0L, entrada.prioridadEfectiva());
            assertNull(procesador.consultar(antiguo.id()).primeraAtencion());
            assertEquals(Duration.ZERO, procesador.consultar(antiguo.id()).procesamientoAcumulado());

            motor.pausar();
            control.avanzarReal(Duration.ofSeconds(200));
            assertEquals(Duration.ofMinutes(25), procesador.consultarCola().getFirst().esperaAcumulada());
            motor.reanudar();
            assertEquals(Duration.ofMinutes(25), procesador.consultarCola().getFirst().esperaAcumulada());
            procesador.cambiarIntervaloAging(Duration.ofMinutes(10));
            assertEquals(List.of(nuevo.id(), antiguo.id()), ids(procesador));
            assertThrows(IllegalArgumentException.class, () -> procesador.cambiarIntervaloAging(Duration.ZERO));
        } finally { liberar.countDown(); }
    }

    @Test void cambiarPoliticaAlteraSiguienteSeleccionSinInterrumpirLaEtapaActiva() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        var ocupado = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        try (var motor = new MotorSimulacion(1, 0.01);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
            motor.iniciar();
            motor.enviarTrabajo(contexto -> { ocupado.countDown(); liberar.await(); });
            assertTrue(ocupado.await(10, TimeUnit.SECONDS));
            var largo = pedido(EXPRES, 20, "GEN-01");
            var corto = pedido(ECONOMICO, 1, "GEN-01");
            procesador.procesar(largo.id());
            procesador.procesar(corto.id());
            var recursosAntes = recursos.estado();
            assertEquals(List.of(largo.id(), corto.id()), ids(procesador));
            assertEquals(recursosAntes, recursos.estado()); // Consultar no reserva ni libera recursos.
            procesador.cambiarPolitica(SJF);
            assertEquals(SJF, procesador.politica());
            assertEquals(List.of(corto.id(), largo.id()), ids(procesador));
            liberar.countDown();
            esperar(avisos, () -> propietario(corto));
            motor.pausar(); // Espera el punto seguro antes de observar el progreso.
            var antes = procesador.consultar(corto.id());
            assertEquals(EstadoPedido.EN_PROCESO, antes.estado());
            assertEquals(0, antes.pasosCompletados());
            procesador.cambiarPolitica(PRIORIDAD);
            assertEquals(antes.recursos(), procesador.consultar(corto.id()).recursos());
            assertFalse(antes.recursos().isEmpty());
            motor.reanudar();
            assertEquals(EstadoPedido.EN_PROCESO, procesador.consultar(corto.id()).estado());
        } finally { liberar.countDown(); recursos.retirarAvisos(avisos); }
    }

    @RepeatedTest(10) void pedidoSinRecursosNoBloqueaOtroMenosPrioritario() throws Exception {
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        var ocupado = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        try (var motor = new MotorSimulacion(1, 0.01);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var bloqueo = recursos.intentarAsignar("externo", new RequisitosRecursos(Map.of(MONTACARGAS, 3))).orElseThrow()) {
            motor.iniciar();
            motor.enviarTrabajo(contexto -> { ocupado.countDown(); liberar.await(); });
            assertTrue(ocupado.await(10, TimeUnit.SECONDS));
            var pesado = pedido(EXPRES, 1, "PES-01");
            var normal = pedido(ECONOMICO, 1, "GEN-01");
            procesador.procesar(pesado.id());
            procesador.procesar(normal.id());
            assertEquals(List.of(pesado.id(), normal.id()), ids(procesador));
            liberar.countDown();
            esperar(avisos, () -> recursos.esperando().keySet().stream().anyMatch(s -> s.startsWith(pesado.id().toString()))
                    && propietario(normal));
            motor.pausar();
            var cola = procesador.consultarCola();
            assertEquals(pesado.id(), cola.getFirst().pedidoId());
            assertEquals("Recursos insuficientes", cola.getFirst().motivo());
            assertEquals(EstadoPedido.EN_PROCESO, procesador.consultar(normal.id()).estado());
            assertEquals(EstadoPedido.ESPERANDO_RECURSOS, procesador.consultar(pesado.id()).estado());
        } finally { liberar.countDown(); recursos.retirarAvisos(avisos); }
    }

    @Test void esperaPorRecursosSeConservaHastaPrimeraAtencionYDuranteLaPausa() throws Exception {
        var control = new MotorControlado();
        var motor = control.motor();
        var avisos = new Semaphore(0);
        recursos.registrarAvisos(avisos);
        motor.registrarAvisos(avisos);
        try (motor; var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var bloqueo = recursos.intentarAsignar("externo", new RequisitosRecursos(Map.of(ENCARGADO_BODEGA, 10))).orElseThrow()) {
            motor.iniciar();
            procesador.cambiarPolitica(PoliticaPlanificacion.AGING);
            var p = pedido(ECONOMICO, 1, "GEN-01");
            procesador.procesar(p.id());
            esperar(avisos, () -> procesador.consultarCola().stream().anyMatch(e -> e.pedidoId().equals(p.id())
                    && e.motivo().equals("Recursos insuficientes")));
            control.avanzarReal(Duration.ofSeconds(20));
            assertEquals(Duration.ofMinutes(20), procesador.consultarCola().getFirst().esperaAcumulada());
            assertEquals(1L, procesador.consultarCola().getFirst().prioridadEfectiva());
            bloqueo.close();
            esperar(avisos, () -> propietario(p));
            motor.pausar();
            var progreso = procesador.consultar(p.id());
            assertEquals(Duration.ofMinutes(20), progreso.primeraAtencion());
            assertEquals(Duration.ofMinutes(20), progreso.esperaAcumulada());
            assertEquals(Duration.ZERO, progreso.procesamientoAcumulado());
            control.avanzarReal(Duration.ofSeconds(100));
            assertEquals(progreso.esperaAcumulada(), procesador.consultar(p.id()).esperaAcumulada());
            assertEquals(Duration.ZERO, procesador.consultar(p.id()).procesamientoAcumulado());
            motor.reanudar();
            control.avanzarReal(Duration.ofMillis(5)); // 300 ms simulados, menores al primer paso.
            motor.pausar();
            var trabajando = procesador.consultar(p.id());
            assertEquals(EstadoPedido.EN_PROCESO, trabajando.estado());
            assertEquals(Duration.ofMinutes(20), trabajando.esperaAcumulada());
            assertEquals(Duration.ofMillis(300), trabajando.procesamientoAcumulado());
            assertEquals(Duration.ofMinutes(20), trabajando.primeraAtencion());
        } finally { motor.retirarAvisos(avisos); recursos.retirarAvisos(avisos); }
    }

    @RepeatedTest(10) void pedidosCompletanCadaPasoUnaVezConPoliticaActiva() throws Exception {
        try (var motor = new MotorSimulacion(2, 1_000_000);
             var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
            motor.iniciar();
            procesador.cambiarPolitica(AGING);
            var general = pedido(ECONOMICO, 2, "GEN-01");
            var fragil = pedido(PRIORITARIO, 1, "FRA-01");
            var a = procesador.procesar(general.id());
            var b = procesador.procesar(fragil.id());
            var resultadoA = a.get(10, TimeUnit.SECONDS);
            var resultadoB = b.get(10, TimeUnit.SECONDS);
            assertEquals(EstadoPedido.COMPLETADO, resultadoA.estado());
            assertEquals(EstadoPedido.COMPLETADO, resultadoB.estado());
            assertEquals(3, resultadoA.pasosTotales());
            assertEquals(4, resultadoB.pasosTotales());
            assertEquals(resultadoA.pasosTotales(), resultadoA.pasosCompletados());
            assertEquals(resultadoB.pasosTotales(), resultadoB.pasosCompletados());
            for (var tipo : gt.edu.usac.cunoc.redxela.recursos.TipoRecurso.values())
                assertEquals(tipo.capacidad(), recursos.disponibles(tipo));
            assertTrue(procesador.consultarCola().isEmpty());
        }
    }

    private List<UUID> ids(ProcesadorPedidos procesador) {
        return procesador.consultarCola().stream().map(ProcesadorPedidos.EntradaCola::pedidoId).toList();
    }

    private boolean propietario(Pedido pedido) {
        return recursos.estado().stream().anyMatch(u -> u.propietario() != null
                && u.propietario().startsWith(pedido.id().toString()));
    }

    private static void esperar(Semaphore avisos, BooleanSupplier condicion) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condicion.getAsBoolean()) {
            long restante = limite - System.nanoTime();
            assertTrue(restante > 0 && avisos.tryAcquire(restante, TimeUnit.NANOSECONDS), "No llegó la señal esperada");
        }
    }
}
