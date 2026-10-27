package gt.edu.usac.cunoc.redxela.api;

import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.metricas.MetricasService;
import gt.edu.usac.cunoc.redxela.pedidos.*;
import gt.edu.usac.cunoc.redxela.recursos.TipoRecurso;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Duraciones de la API siempre en milisegundos simulados. */
public final class ContratosApi {
    private ContratosApi() { }
    public record ClienteSolicitud(String nombre, String correo, NivelServicio servicioPreferido) { }
    public record PedidoSolicitud(UUID clienteId, NivelServicio nivelServicio, List<SolicitudLineaPedido> lineas) { }
    public record RecepcionSolicitud(String productoId, int cantidad) { }
    public record VelocidadSolicitud(double factor) { }
    public record IntervaloSolicitud(long intervaloMillis) { }
    public record PlanificacionSolicitud(gt.edu.usac.cunoc.redxela.planificacion.PoliticaPlanificacion politica, Long intervaloAgingMillis) { }
    public record ConflictoSolicitud(UUID pedidoA, UUID pedidoB) { }
    public record GanadorSolicitud(UUID pedidoGanador) { }
    public record ComparacionSolicitud(long observacionMillis, double factor) { }
    public record Progreso(UUID pedidoId, EstadoPedido estado, EtapaPedido etapa, int pasosCompletados, int pasosTotales,
                           double porcentaje, List<String> recursos, Long restanteEstimadoMillis, String error,
                           Long primeraAtencionMillis, long esperaAcumuladaMillis, long procesamientoAcumuladoMillis) { }
    public record PedidoVista(Pedido pedido, Progreso progreso) { }
    public record Cola(UUID pedidoId, NivelServicio nivelServicio, long unidades, int orden, EstadoPedido estado,
                       long esperaAcumuladaMillis, Long prioridadEfectiva, String motivo) { }
    public record Nivel(NivelServicio nivel, long admitidos, long completados, long primeraAtencionPromedioMillis,
                        long cumplen, double porcentajeCumplimiento, long pendientesVencidos) { }
    public record Utilizacion(TipoRecurso tipo, int unidades, long tiempoOcupadoMillis, double porcentaje) { }
    public record Metricas(long corrida, long tiempoObservadoMillis, long admitidos, long completados, long procesando,
                           long esperandoInventario, long esperandoRecursos, long errores, long cancelados,
                           long primeraAtencionPromedioMillis, long esperaPromedioMillis, long procesamientoPromedioMillis,
                           long totalPromedioMillis, double cumplimiento, List<Nivel> niveles, List<Utilizacion> recursos) { }
    static Long ms(Duration d) { return d == null ? null : d.toMillis(); }
    static Progreso progreso(ProcesadorPedidos.Progreso p) {
        return p == null ? null : new Progreso(p.pedidoId(), p.estado(), p.etapa(), p.pasosCompletados(), p.pasosTotales(),
                p.porcentaje(), p.recursos(), ms(p.restanteEstimado()), p.error(), ms(p.primeraAtencion()),
                p.esperaAcumulada().toMillis(), p.procesamientoAcumulado().toMillis());
    }
    static Cola cola(ProcesadorPedidos.EntradaCola e) {
        return new Cola(e.pedidoId(), e.nivelServicio(), e.unidades(), e.orden(), e.estado(), e.esperaAcumulada().toMillis(), e.prioridadEfectiva(), e.motivo());
    }
    static Metricas metricas(MetricasService.Resumen m) {
        return new Metricas(m.corrida(), m.tiempoObservado().toMillis(), m.admitidos(), m.completados(), m.procesando(),
                m.esperandoInventario(), m.esperandoRecursos(), m.errores(), m.cancelados(), m.primeraAtencionPromedio().toMillis(),
                m.esperaPromedio().toMillis(), m.procesamientoPromedio().toMillis(), m.totalPromedio().toMillis(), m.cumplimiento(),
                m.niveles().stream().map(n -> new Nivel(n.nivel(), n.admitidos(), n.completados(), n.primeraAtencionPromedio().toMillis(),
                        n.cumplen(), n.porcentajeCumplimiento(), n.pendientesVencidos())).toList(),
                m.recursos().stream().map(u -> new Utilizacion(u.tipo(), u.unidades(), u.tiempoOcupado().toMillis(), u.porcentaje())).toList());
    }
}
