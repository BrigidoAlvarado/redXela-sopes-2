package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.catalogo.TipoMercancia;
import gt.edu.usac.cunoc.redxela.recursos.RequisitosRecursos;

/** Descripción inmutable, no una tarea ejecutada. Posición es el checkpoint futuro. */
public record PasoTrabajo(int posicion, EtapaPedido etapa, TipoMercancia tipo, long unidades) {
    public PasoTrabajo {
        if (posicion < 0 || unidades < 1 || etapa == null || tipo == null
                || !tipo.requisitosPorEtapa().containsKey(etapa)) {
            throw new IllegalArgumentException("Paso de trabajo inválido");
        }
    }

    public RequisitosRecursos requisitos() { return tipo.requisitosPorEtapa().get(etapa); }
}
