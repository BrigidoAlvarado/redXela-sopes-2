package gt.edu.usac.cunoc.redxela.metricas;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.inventario.InventarioService;
import gt.edu.usac.cunoc.redxela.pedidos.PedidoService;
import gt.edu.usac.cunoc.redxela.pedidos.ProcesadorPedidos;
import gt.edu.usac.cunoc.redxela.pedidos.SolicitudLineaPedido;
import gt.edu.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.recursos.TipoRecurso;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/** Tres corridas aisladas con la misma carga fija; no modifica la simulación del usuario. */
public final class ComparacionPoliticas {
    public record PedidoEscenario(NivelServicio nivel, String producto, int unidades) { }
    public record Resultado(PoliticaPlanificacion politica, MetricasService.Resumen metricas, long pendientes) { }
    public record Comparacion(List<PedidoEscenario> escenario, Duration observacion, double factor,
                              List<Resultado> resultados, Map<String, List<PoliticaPlanificacion>> mejores) { }
    private static final List<PedidoEscenario> ESCENARIO = List.of(
            new PedidoEscenario(NivelServicio.ECONOMICO, "GEN-01", 12),
            new PedidoEscenario(NivelServicio.EXPRES, "GEN-01", 3),
            new PedidoEscenario(NivelServicio.PROGRAMADO, "FRA-01", 6),
            new PedidoEscenario(NivelServicio.PRIORITARIO, "GEN-01", 7),
            new PedidoEscenario(NivelServicio.ESTANDAR, "FRA-01", 2),
            new PedidoEscenario(NivelServicio.ECONOMICO, "GEN-01", 1));

    public Comparacion comparar(Duration observacion, double factor) throws InterruptedException {
        Objects.requireNonNull(observacion);
        if (observacion.isNegative() || observacion.isZero()) throw new IllegalArgumentException("Observación debe ser positiva");
        observacion.toNanos();
        var resultados = new ArrayList<Resultado>();
        for (PoliticaPlanificacion politica : PoliticaPlanificacion.values())
            resultados.add(ejecutar(politica, observacion, factor));
        var mejores = new LinkedHashMap<String, List<PoliticaPlanificacion>>();
        mejores.put("completados (mayor)", extremos(resultados, r -> r.metricas().completados(), true, false));
        mejores.put("pendientes (menor)", extremos(resultados, Resultado::pendientes, false, false));
        mejores.put("primera atención (menor)", extremos(resultados, r -> r.metricas().primeraAtencionPromedio().toNanos(), false, true));
        mejores.put("espera (menor)", extremos(resultados, r -> r.metricas().esperaPromedio().toNanos(), false, true));
        mejores.put("procesamiento (menor)", extremos(resultados, r -> r.metricas().procesamientoPromedio().toNanos(), false, true));
        mejores.put("cumplimiento (mayor)", extremos(resultados, r -> r.metricas().cumplimiento(), true, true));
        for (TipoRecurso tipo : TipoRecurso.values())
            mejores.put("utilización " + tipo + " (mayor)", extremos(resultados,
                    r -> r.metricas().recursos().stream().filter(u -> u.tipo() == tipo).findFirst().orElseThrow().porcentaje(), true, false));
        return new Comparacion(ESCENARIO, observacion, factor, List.copyOf(resultados), Map.copyOf(mejores));
    }

    private static Resultado ejecutar(PoliticaPlanificacion politica, Duration observacion, double factor) throws InterruptedException {
        var catalogo = new CatalogoProductos(); var clientes = new ClienteService();
        var pedidos = new PedidoService(catalogo, clientes); var inventario = new InventarioService(catalogo);
        try (var motor = new MotorSimulacion(1, factor)) {
            var recursos = new GestorRecursos(motor.relojMedicion());
            try (var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario)) {
                var metricas = new MetricasService(motor, procesador, recursos);
                inventario.almacenar(UUID.randomUUID(), "GEN-01", 40);
                inventario.almacenar(UUID.randomUUID(), "FRA-01", 40);
                var iniciado = new CountDownLatch(1); var liberar = new CountDownLatch(1);
                motor.iniciar(); procesador.cambiarPolitica(politica);
                try {
                    // El único trabajador espera mientras se admite toda la carga en el mismo orden.
                    motor.enviarTrabajo(ctx -> { iniciado.countDown(); liberar.await(); });
                    if (!iniciado.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("No inició el trabajador");
                    int i = 0;
                    for (PedidoEscenario entrada : ESCENARIO) {
                        var cliente = clientes.registrar("Cliente " + i, "cliente" + i + "@comparacion.local", entrada.nivel());
                        var pedido = pedidos.crear(cliente.id(), entrada.nivel(), List.of(new SolicitudLineaPedido(entrada.producto(), entrada.unidades())));
                        procesador.procesar(pedido.id()); i++;
                    }
                    liberar.countDown();
                    motor.esperarTiempo(motor.corridaActual(), observacion);
                    motor.pausar();
                    var resumen = metricas.resumenActual();
                    return new Resultado(politica, resumen, resumen.admitidos() - resumen.completados() - resumen.errores() - resumen.cancelados());
                } finally { liberar.countDown(); }
            }
        }
    }

    private static List<PoliticaPlanificacion> extremos(List<Resultado> resultados, ToDoubleFunction<Resultado> indicador,
                                                         boolean mayor, boolean requiereCompletados) {
        var validos = resultados.stream().filter(r -> !requiereCompletados || r.metricas().completados() > 0).toList();
        if (validos.isEmpty()) return List.of();
        double extremo = mayor ? validos.stream().mapToDouble(indicador).max().orElseThrow()
                : validos.stream().mapToDouble(indicador).min().orElseThrow();
        return validos.stream().filter(r -> Double.compare(indicador.applyAsDouble(r), extremo) == 0)
                .map(Resultado::politica).toList();
    }
}
