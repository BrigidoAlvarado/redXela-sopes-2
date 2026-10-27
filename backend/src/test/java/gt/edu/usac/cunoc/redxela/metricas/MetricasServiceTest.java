package gt.edu.usac.cunoc.redxela.metricas;

import gt.edu.usac.cunoc.redxela.catalogo.*;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.inventario.InventarioService;
import gt.edu.usac.cunoc.redxela.pedidos.*;
import gt.edu.usac.cunoc.redxela.recursos.*;
import gt.edu.usac.cunoc.redxela.simulacion.MotorControlado;
import gt.edu.usac.cunoc.redxela.conflicto.DemostracionDeadlock;
import gt.edu.usac.cunoc.redxela.recepcion.RecepcionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class MetricasServiceTest {
    private final CatalogoProductos catalogo = new CatalogoProductos();
    private final ClienteService clientes = new ClienteService();
    private final PedidoService pedidos = new PedidoService(catalogo, clientes);
    private final InventarioService inventario = new InventarioService(catalogo);
    private final MotorControlado control = new MotorControlado();
    private final Semaphore avisos = new Semaphore(0);

    private Pedido pedido(NivelServicio nivel, String producto) {
        var cliente = clientes.registrar("Cliente", UUID.randomUUID() + "@ejemplo.com", nivel);
        return pedidos.crear(cliente.id(), nivel, List.of(new SolicitudLineaPedido(producto, 1)));
    }

    @Test void utilizacionUnidadesPausaYSeparacionDeCorridas() throws Exception {
        try (var motor = control.motor()) {
            var recursos = new GestorRecursos(motor.relojMedicion());
            try (var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
                var metricas = new MetricasService(motor, procesador, recursos);
                motor.iniciar();
                var cero = metricas.resumenActual();
                assertEquals(0, cero.admitidos()); assertEquals(Duration.ZERO, cero.esperaPromedio());
                var asignacion = recursos.intentarAsignar("empaque", new RequisitosRecursos(Map.of(TipoRecurso.ESTACION_EMPAQUE, 2))).orElseThrow();
                control.avanzarReal(Duration.ofSeconds(1)); // 60 segundos simulados.
                motor.pausar();
                var pausa = metricas.resumenActual();
                control.avanzarReal(Duration.ofSeconds(10));
                assertEquals(pausa.recursos(), metricas.resumenActual().recursos());
                motor.reanudar(); asignacion.close(); asignacion.close();
                control.avanzarReal(Duration.ofSeconds(1));
                var empaque = metricas.resumenActual().recursos().stream().filter(u -> u.tipo() == TipoRecurso.ESTACION_EMPAQUE).findFirst().orElseThrow();
                assertEquals(Duration.ofSeconds(120), empaque.tiempoOcupado());
                assertEquals(100.0 / 6, empaque.porcentaje(), 0.0001);
                motor.detener(); motor.iniciar();
                var nueva = metricas.resumenActual();
                assertEquals(2, nueva.corrida());
                assertEquals(0, nueva.admitidos());
                assertTrue(nueva.recursos().stream().allMatch(u -> u.porcentaje() == 0));
            }
        }
    }

    @Test void scannerRecibeTiempoDeRecepcionYDespachoSinDuplicar() throws Exception {
        try (var motor = control.motor()) {
            var recursos = new GestorRecursos(motor.relojMedicion());
            try (var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
                var metricas = new MetricasService(motor, procesador, recursos);
                motor.iniciar();
                var requisitos = new RequisitosRecursos(Map.of(TipoRecurso.ESCANER, 1));
                try (var recepcion = recursos.intentarAsignar("recepcion", requisitos).orElseThrow()) {
                    control.avanzarReal(Duration.ofSeconds(1));
                }
                try (var despacho = recursos.intentarAsignar("despacho", requisitos).orElseThrow()) {
                    control.avanzarReal(Duration.ofSeconds(2));
                }
                control.avanzarReal(Duration.ofSeconds(1));
                var scanner = metricas.resumenActual().recursos().stream().filter(u -> u.tipo() == TipoRecurso.ESCANER).findFirst().orElseThrow();
                assertEquals(Duration.ofSeconds(180), scanner.tiempoOcupado());
                assertEquals(75, scanner.porcentaje(), 0.001);
                assertTrue(metricas.resumenActual().recursos().stream().allMatch(u -> u.porcentaje() <= 100));
            }
        }
    }

    @Test void pedidosPendientesVencidosSeCuentanPorNivelSinIncluirNoAdmitidos() throws Exception {
        try (var motor = control.motor()) {
            var recursos = new GestorRecursos(motor.relojMedicion());
            try (var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
                var metricas = new MetricasService(motor, procesador, recursos);
                motor.iniciar();
                pedido(NivelServicio.EXPRES, "GEN-01"); // Creado, no admitido.
                for (var nivel : NivelServicio.values()) procesador.procesar(pedido(nivel, "GEN-01").id());
                control.avanzarReal(Duration.ofSeconds(121)); // 121 minutos simulados.
                var resumen = metricas.resumenActual();
                assertEquals(5, resumen.admitidos());
                assertEquals(5, resumen.esperandoInventario());
                assertEquals(0, resumen.completados());
                assertEquals(Duration.ZERO, resumen.primeraAtencionPromedio());
                assertTrue(resumen.niveles().stream().allMatch(n -> n.admitidos() == 1 && n.pendientesVencidos() == 1
                        && n.completados() == 0 && n.porcentajeCumplimiento() == 0));
                motor.detener(); motor.iniciar();
                assertEquals(0, metricas.resumenActual().admitidos());
            }
        }
    }

    @Test void detencionRegistraErroresDeLaCorridaSinContaminacionPosterior() throws Exception {
        try (var motor = control.motor()) {
            var recursos = new GestorRecursos(motor.relojMedicion());
            try (var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
                var metricas = new MetricasService(motor, procesador, recursos);
                motor.iniciar();
                var pendiente = procesador.procesar(pedido(NivelServicio.EXPRES, "GEN-01").id());
                motor.detener();
                assertThrows(ExecutionException.class, () -> pendiente.get(10, TimeUnit.SECONDS));
                assertEquals(1, metricas.resumenActual().errores());
                assertEquals(0, metricas.resumenActual().cancelados());
                motor.iniciar();
                assertEquals(0, metricas.resumenActual().admitidos());
                assertEquals(0, metricas.resumenActual().errores());
            }
        }
    }

    @Test void consultasConcurrentesNoModificanEjecucionNiPermisos() throws Exception {
        try (var motor = new gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion(3, 1_000_000)) {
            var recursos = new GestorRecursos(motor.relojMedicion());
            try (var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
                 var lectores = Executors.newFixedThreadPool(3)) {
                var metricas = new MetricasService(motor, procesador, recursos);
                inventario.almacenar(UUID.randomUUID(), "GEN-01", 8);
                motor.iniciar();
                var futuros = new ArrayList<Future<ProcesadorPedidos.Progreso>>();
                for (int i = 0; i < 8; i++) futuros.add(procesador.procesar(pedido(NivelServicio.EXPRES, "GEN-01").id()));
                var inicio = new CountDownLatch(1);
                var lecturas = new ArrayList<Future<?>>();
                for (int i = 0; i < 3; i++) lecturas.add(lectores.submit(() -> {
                    inicio.await();
                    for (int j = 0; j < 50; j++) {
                        var resumen = metricas.resumenActual();
                        assertTrue(resumen.recursos().stream().allMatch(u -> u.porcentaje() >= 0 && u.porcentaje() <= 100));
                    }
                    return null;
                }));
                inicio.countDown();
                for (var f : lecturas) f.get(10, TimeUnit.SECONDS);
                for (var f : futuros) assertEquals(EstadoPedido.COMPLETADO, f.get(10, TimeUnit.SECONDS).estado());
                assertEquals(8, metricas.resumenActual().completados());
                assertEquals(0, inventario.existencia("GEN-01").fisico());
            }
        }
    }

    @Test void usoRealDeRecepcionYDespachoCuentaEnElEscaner() throws Exception {
        var motor = control.motor(); var recursos = new GestorRecursos(motor.relojMedicion());
        recursos.registrarAvisos(avisos);
        try (motor; var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var recepcion = new RecepcionService(catalogo, inventario, recursos, motor, 5)) {
            procesador.registrarAvisos(avisos); recepcion.registrarAvisos(avisos);
            var metricas = new MetricasService(motor, procesador, recursos);
            motor.iniciar();
            var ingreso = recepcion.registrar("GEN-01", 1);
            esperar(() -> recursos.estado().stream().anyMatch(u -> ("recepcion:" + ingreso).equals(u.propietario())));
            esperarDuracionActiva();
            control.avanzarReal(Duration.ofSeconds(1));
            esperar(() -> recepcion.consultar(ingreso).estado() == RecepcionService.Estado.ALMACENADA);
            var p = pedido(NivelServicio.EXPRES, "GEN-01");
            var futuro = procesador.procesar(p.id());
            for (int etapa = 0; etapa < 3; etapa++) {
                int paso = etapa;
                esperar(() -> procesador.consultar(p.id()).pasosCompletados() == paso
                        && procesador.consultar(p.id()).estado() == EstadoPedido.EN_PROCESO);
                esperarDuracionActiva();
                control.avanzarReal(Duration.ofMillis(50));
            }
            futuro.get(10, TimeUnit.SECONDS);
            esperar(() -> recursos.disponibles(TipoRecurso.ESCANER) == 1);
            var scanner = metricas.resumenActual().recursos().stream().filter(u -> u.tipo() == TipoRecurso.ESCANER).findFirst().orElseThrow();
            assertEquals(Duration.ofSeconds(63), scanner.tiempoOcupado());
        } finally { recursos.retirarAvisos(avisos); }
    }

    @Test void deadlockAcumulaEsperaYOcupacionSinProcesamientoFicticio() throws Exception {
        var motor = control.motor(); var recursos = new GestorRecursos(motor.relojMedicion());
        recursos.registrarAvisos(avisos);
        try (motor; var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var demostracion = new DemostracionDeadlock(motor, procesador, recursos)) {
            procesador.registrarAvisos(avisos); var metricas = new MetricasService(motor, procesador, recursos);
            motor.iniciar();
            var a = pedido(NivelServicio.EXPRES, "GEN-01"); var b = pedido(NivelServicio.EXPRES, "GEN-01");
            procesador.procesar(a.id()); procesador.procesar(b.id());
            demostracion.iniciar(a.id(), b.id());
            inventario.almacenar(UUID.randomUUID(), "GEN-01", 2);
            for (int i = 0; i < 4; i++) {
                int realizados = i;
                esperar(() -> procesador.consultar(a.id()).pasosCompletados() + procesador.consultar(b.id()).pasosCompletados() == realizados
                        && (procesador.consultar(a.id()).estado() == EstadoPedido.EN_PROCESO || procesador.consultar(b.id()).estado() == EstadoPedido.EN_PROCESO));
                esperarDuracionActiva();
                control.avanzarReal(Duration.ofMillis(50));
                esperar(() -> procesador.consultar(a.id()).pasosCompletados() + procesador.consultar(b.id()).pasosCompletados() > realizados);
            }
            esperar(() -> demostracion.consultar().deadlock());
            var antes = procesador.consultar(a.id()); var ocupacionAntes = metricas.resumenActual();
            control.avanzarReal(Duration.ofSeconds(1));
            var despues = procesador.consultar(a.id()); var ocupacionDespues = metricas.resumenActual();
            assertEquals(antes.procesamientoAcumulado(), despues.procesamientoAcumulado());
            assertEquals(antes.esperaAcumulada().plusSeconds(60), despues.esperaAcumulada());
            for (TipoRecurso tipo : List.of(TipoRecurso.ESCANER, TipoRecurso.AREA_CARGA)) {
                var inicial = ocupacionAntes.recursos().stream().filter(u -> u.tipo() == tipo).findFirst().orElseThrow();
                var finalizado = ocupacionDespues.recursos().stream().filter(u -> u.tipo() == tipo).findFirst().orElseThrow();
                assertEquals(inicial.tiempoOcupado().plusSeconds(60), finalizado.tiempoOcupado());
                assertTrue(finalizado.porcentaje() <= 100);
            }
        } finally { recursos.retirarAvisos(avisos); }
    }

    @ParameterizedTest @EnumSource(NivelServicio.class)
    void promediosUsanTiemposSimuladosDePedidosCompletados(NivelServicio servicio) throws Exception {
        var motor = control.motor(); var recursos = new GestorRecursos(motor.relojMedicion());
        recursos.registrarAvisos(avisos);
        var ocupado = new CountDownLatch(1); var liberar = new CountDownLatch(1);
        try (motor; var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
            procesador.registrarAvisos(avisos);
            var metricas = new MetricasService(motor, procesador, recursos);
            inventario.almacenar(UUID.randomUUID(), "GEN-01", 1);
            motor.iniciar();
            motor.enviarTrabajo(ctx -> { ocupado.countDown(); liberar.await(); });
            assertTrue(ocupado.await(10, TimeUnit.SECONDS));
            var pedido = pedido(servicio, "GEN-01");
            var resultado = procesador.procesar(pedido.id());
            control.avanzarReal(Duration.ofSeconds(1)); // Espera inicial: 60 s simulados.
            liberar.countDown();
            for (int etapa = 0; etapa < 3; etapa++) {
                int paso = etapa;
                esperar(() -> procesador.consultar(pedido.id()).pasosCompletados() == paso
                        && procesador.consultar(pedido.id()).estado() == EstadoPedido.EN_PROCESO);
                esperarDuracionActiva();
                control.avanzarReal(Duration.ofMillis(50)); // 3 s simulados por etapa.
            }
            assertEquals(EstadoPedido.COMPLETADO, resultado.get(10, TimeUnit.SECONDS).estado());
            var resumen = metricas.resumenActual();
            assertEquals(1, resumen.completados());
            assertEquals(Duration.ofSeconds(60), resumen.primeraAtencionPromedio());
            assertEquals(Duration.ofSeconds(60), resumen.esperaPromedio());
            assertEquals(Duration.ofSeconds(9), resumen.procesamientoPromedio());
            assertEquals(Duration.ofSeconds(69), resumen.totalPromedio());
            var nivel = resumen.niveles().stream().filter(n -> n.nivel() == servicio).findFirst().orElseThrow();
            boolean aTiempo = Duration.ofSeconds(60).compareTo(servicio.objetivoEspera()) <= 0;
            assertEquals(aTiempo ? 1 : 0, nivel.cumplen()); assertEquals(aTiempo ? 100 : 0, nivel.porcentajeCumplimiento());
        } finally { liberar.countDown(); recursos.retirarAvisos(avisos); }
    }

    @Test void promediosCombinanDosPedidosConDiferentesEsperas() throws Exception {
        var motor = control.motor(); var recursos = new GestorRecursos(motor.relojMedicion());
        recursos.registrarAvisos(avisos);
        var ocupado = new CountDownLatch(1); var liberar = new CountDownLatch(1);
        try (motor; var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
            procesador.registrarAvisos(avisos);
            var metricas = new MetricasService(motor, procesador, recursos);
            inventario.almacenar(UUID.randomUUID(), "GEN-01", 2);
            motor.iniciar();
            motor.enviarTrabajo(ctx -> { ocupado.countDown(); liberar.await(); });
            assertTrue(ocupado.await(10, TimeUnit.SECONDS));
            var a = pedido(NivelServicio.EXPRES, "GEN-01");
            var b = pedido(NivelServicio.PRIORITARIO, "GEN-01");
            var fa = procesador.procesar(a.id()); var fb = procesador.procesar(b.id());
            control.avanzarReal(Duration.ofSeconds(1));
            liberar.countDown();
            for (int i = 0; i < 6; i++) {
                int terminados = i;
                esperar(() -> procesador.consultar(a.id()).pasosCompletados() + procesador.consultar(b.id()).pasosCompletados() == terminados
                        && (procesador.consultar(a.id()).estado() == EstadoPedido.EN_PROCESO || procesador.consultar(b.id()).estado() == EstadoPedido.EN_PROCESO));
                esperarDuracionActiva();
                control.avanzarReal(Duration.ofMillis(50));
                esperar(() -> procesador.consultar(a.id()).pasosCompletados() + procesador.consultar(b.id()).pasosCompletados() > terminados);
            }
            fa.get(10, TimeUnit.SECONDS); fb.get(10, TimeUnit.SECONDS);
            var resumen = metricas.resumenActual();
            assertEquals(2, resumen.completados());
            assertEquals(Duration.ofMillis(64_500), resumen.primeraAtencionPromedio());
            assertEquals(Duration.ofMillis(64_500), resumen.esperaPromedio());
            assertEquals(Duration.ofSeconds(9), resumen.procesamientoPromedio());
            assertEquals(Duration.ofMillis(73_500), resumen.totalPromedio());
            assertEquals(50, resumen.cumplimiento());
        } finally { liberar.countDown(); recursos.retirarAvisos(avisos); }
    }

    private void esperar(BooleanSupplier condicion) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condicion.getAsBoolean()) {
            long restante = limite - System.nanoTime();
            assertTrue(restante > 0 && avisos.tryAcquire(restante, TimeUnit.NANOSECONDS), "No llegó la señal esperada");
        }
    }
    private static void esperarDuracionActiva() {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < limite) {
            if (Thread.getAllStackTraces().entrySet().stream().anyMatch(e -> e.getKey().getName().startsWith("redxela-corrida-")
                    && e.getKey().getState() == Thread.State.TIMED_WAITING && Arrays.stream(e.getValue())
                    .anyMatch(f -> f.getClassName().contains("MotorSimulacion$ContextoTrabajo") && f.getMethodName().equals("esperar")))) return;
            Thread.yield();
        }
        fail("El trabajador no inició la espera de procesamiento");
    }
}
