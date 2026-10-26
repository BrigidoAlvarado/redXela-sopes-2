package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.catalogo.TipoMercancia;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static gt.edu.usac.cunoc.redxela.dominio.Validaciones.requerido;

/** Ruta derivada una sola vez. No hay progreso, reloj ni ejecución de etapas en F1. */
public final class PlanTrabajo {
    private final UUID pedidoId;
    private final List<PasoTrabajo> pasos;

    private PlanTrabajo(UUID pedidoId, List<PasoTrabajo> pasos) {
        this.pedidoId = pedidoId;
        this.pasos = List.copyOf(pasos);
    }

    public static PlanTrabajo paraPedido(Pedido pedido) {
        requerido(pedido, "Pedido");
        var unidadesPorTipo = new EnumMap<TipoMercancia, Long>(TipoMercancia.class);
        for (LineaPedido linea : pedido.lineas()) {
            unidadesPorTipo.merge(linea.producto().tipo(), (long) linea.cantidad(), Math::addExact);
        }
        var pasos = new ArrayList<PasoTrabajo>();
        // Todas las retiradas preceden al empaque, y todos los empaques al despacho.
        // Un tipo participa una vez por etapa, aunque haya varios productos del mismo tipo.
        for (EtapaPedido etapa : EtapaPedido.values()) {
            for (TipoMercancia tipo : TipoMercancia.values()) {
                if (unidadesPorTipo.containsKey(tipo) && tipo.requisitosPorEtapa().containsKey(etapa)) {
                    pasos.add(new PasoTrabajo(pasos.size(), etapa, tipo, unidadesPorTipo.get(tipo)));
                }
            }
        }
        return new PlanTrabajo(pedido.id(), pasos);
    }

    public UUID pedidoId() { return pedidoId; }
    public List<PasoTrabajo> pasos() { return pasos; }
}
