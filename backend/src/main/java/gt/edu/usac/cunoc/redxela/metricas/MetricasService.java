package gt.edu.usac.cunoc.redxela.metricas;

import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.pedidos.EstadoPedido;
import gt.edu.usac.cunoc.redxela.pedidos.ProcesadorPedidos;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.recursos.TipoRecurso;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Calcula bajo demanda; no inicia hilos ni conserva eventos históricos. */
public final class MetricasService {
    private final MotorSimulacion motor;
    private final ProcesadorPedidos procesador;
    private final GestorRecursos recursos;
    public MetricasService(MotorSimulacion motor, ProcesadorPedidos procesador, GestorRecursos recursos) {
        this.motor = Objects.requireNonNull(motor); this.procesador = Objects.requireNonNull(procesador);
        this.recursos = Objects.requireNonNull(recursos);
    }
    private record Copia(long corrida, long tiempo, List<ProcesadorPedidos.DatoMetrica> pedidos,
                         GestorRecursos.FotoOcupacion ocupacion) { }
    public record Nivel(NivelServicio nivel, long admitidos, long completados, Duration primeraAtencionPromedio,
                        long cumplen, double porcentajeCumplimiento, long pendientesVencidos) { }
    public record Utilizacion(TipoRecurso tipo, int unidades, Duration tiempoOcupado, double porcentaje) { }
    public record Resumen(long corrida, Duration tiempoObservado, long admitidos, long completados, long procesando,
                          long esperandoInventario, long esperandoRecursos, long errores, long cancelados,
                          Duration primeraAtencionPromedio, Duration esperaPromedio, Duration procesamientoPromedio,
                          Duration totalPromedio, double cumplimiento, List<Nivel> niveles, List<Utilizacion> recursos) { }

    public Resumen resumenActual() {
        // Bajo el lock del motor solo se copian estados; los promedios se calculan después.
        Copia copia = motor.conEstado(estado -> {
            var ocupacion = recursos.ocupacion();
            long tiempo = ocupacion.corrida() == estado.corrida() ? ocupacion.tiempo() : estado.tiempoSimulado();
            return new Copia(estado.corrida(), tiempo, procesador.datosMetricas(estado.corrida(), tiempo), ocupacion);
        });
        var todos = copia.pedidos();
        var completos = todos.stream().filter(p -> p.estado() == EstadoPedido.COMPLETADO).toList();
        var niveles = Arrays.stream(NivelServicio.values()).map(n -> {
            var admitidos = todos.stream().filter(p -> p.nivel() == n).toList();
            var terminados = admitidos.stream().filter(p -> p.estado() == EstadoPedido.COMPLETADO).toList();
            long cumplen = terminados.stream().filter(p -> p.primeraAtencion() != null
                    && p.primeraAtencion().compareTo(n.objetivoEspera()) <= 0).count();
            long vencidos = admitidos.stream().filter(p -> p.primeraAtencion() == null
                    && p.estado() != EstadoPedido.ERROR && p.estado() != EstadoPedido.CANCELADO
                    && p.estado() != EstadoPedido.COMPLETADO && p.desdeLlegada().compareTo(n.objetivoEspera()) > 0).count();
            return new Nivel(n, admitidos.size(), terminados.size(), promedio(terminados, ProcesadorPedidos.DatoMetrica::primeraAtencion),
                    cumplen, porcentaje(cumplen, terminados.size()), vencidos);
        }).toList();
        var utilizacion = Arrays.stream(TipoRecurso.values()).map(tipo -> {
            double nanos = copia.ocupacion().unidades().stream().filter(u -> u.tipo() == tipo)
                    .mapToDouble(GestorRecursos.Ocupacion::nanosOcupados).sum();
            double porcentaje = copia.tiempo() == 0 ? 0 : Math.min(100, nanos * 100 / ((double) copia.tiempo() * tipo.capacidad()));
            return new Utilizacion(tipo, tipo.capacidad(), Duration.ofNanos((long) nanos), porcentaje);
        }).toList();
        long cumplen = niveles.stream().mapToLong(Nivel::cumplen).sum();
        return new Resumen(copia.corrida(), Duration.ofNanos(copia.tiempo()), todos.size(), completos.size(),
                contar(todos, EstadoPedido.EN_PROCESO), contar(todos, EstadoPedido.ESPERANDO_INVENTARIO),
                contar(todos, EstadoPedido.ESPERANDO_RECURSOS) + contar(todos, EstadoPedido.EN_COLA)
                        + contar(todos, EstadoPedido.BLOQUEADO_DEADLOCK) + contar(todos, EstadoPedido.RECUPERANDO),
                contar(todos, EstadoPedido.ERROR), contar(todos, EstadoPedido.CANCELADO),
                promedio(completos, ProcesadorPedidos.DatoMetrica::primeraAtencion),
                promedio(completos, ProcesadorPedidos.DatoMetrica::espera),
                promedio(completos, ProcesadorPedidos.DatoMetrica::procesamiento),
                promedio(completos, ProcesadorPedidos.DatoMetrica::total), porcentaje(cumplen, completos.size()), niveles, utilizacion);
    }
    private static long contar(List<ProcesadorPedidos.DatoMetrica> datos, EstadoPedido estado) {
        return datos.stream().filter(d -> d.estado() == estado).count();
    }
    private static double porcentaje(long parte, long total) { return total == 0 ? 0 : 100.0 * parte / total; }
    private static Duration promedio(List<ProcesadorPedidos.DatoMetrica> datos, Function<ProcesadorPedidos.DatoMetrica, Duration> campo) {
        var valores = datos.stream().map(campo).filter(Objects::nonNull).toList();
        if (valores.isEmpty()) return Duration.ZERO;
        return Duration.ofNanos((long) valores.stream().mapToDouble(Duration::toNanos).average().orElse(0));
    }
}
