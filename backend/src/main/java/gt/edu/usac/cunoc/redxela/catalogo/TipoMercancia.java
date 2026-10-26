package gt.edu.usac.cunoc.redxela.catalogo;

import gt.edu.usac.cunoc.redxela.pedidos.EtapaPedido;
import gt.edu.usac.cunoc.redxela.recursos.RequisitosRecursos;
import gt.edu.usac.cunoc.redxela.recursos.TipoRecurso;

import java.util.Map;

import static gt.edu.usac.cunoc.redxela.pedidos.EtapaPedido.*;
import static gt.edu.usac.cunoc.redxela.recursos.TipoRecurso.*;

/** Perfiles distintos del diseño aprobado. Servicio y mercancía son clasificaciones independientes. */
public enum TipoMercancia {
    GENERAL("General", "Artículos pequeños de manejo ordinario", Map.of(
            RETIRO, recursos(Map.of(ENCARGADO_BODEGA, 1)),
            EMPAQUE, recursos(Map.of(ENCARGADO_BODEGA, 1, ESTACION_EMPAQUE, 1)),
            DESPACHO, recursos(Map.of(ENCARGADO_BODEGA, 1, AREA_CARGA, 1, ESCANER, 1)))),
    FRAGIL("Frágil", "Artículos con protección y revisión de integridad", Map.of(
            RETIRO, recursos(Map.of(ENCARGADO_BODEGA, 1)),
            EMPAQUE, recursos(Map.of(ENCARGADO_BODEGA, 1, ESTACION_EMPAQUE, 1)),
            CALIDAD, recursos(Map.of(ENCARGADO_BODEGA, 1, ESTACION_CALIDAD, 1)),
            DESPACHO, recursos(Map.of(ENCARGADO_BODEGA, 1, AREA_CARGA, 1, ESCANER, 1)))),
    PESADO("Pesado", "Bultos que requieren movimiento mecánico", Map.of(
            RETIRO, recursos(Map.of(ENCARGADO_BODEGA, 2, MONTACARGAS, 1)),
            EMPAQUE, recursos(Map.of(ENCARGADO_BODEGA, 1, ESTACION_EMPAQUE, 1)),
            DESPACHO, recursos(Map.of(ENCARGADO_BODEGA, 2, MONTACARGAS, 1, AREA_CARGA, 1, ESCANER, 1)))),
    VOLUMINOSO("Voluminoso", "Bultos que necesitan dos superficies de empaque", Map.of(
            RETIRO, recursos(Map.of(ENCARGADO_BODEGA, 2, MONTACARGAS, 1)),
            EMPAQUE, recursos(Map.of(ENCARGADO_BODEGA, 2, ESTACION_EMPAQUE, 2)),
            DESPACHO, recursos(Map.of(ENCARGADO_BODEGA, 2, MONTACARGAS, 1, AREA_CARGA, 1, ESCANER, 1)))),
    ELECTRONICA_PALETIZADA("Electrónica paletizada", "Equipos sobre pallet con verificación funcional", Map.of(
            RETIRO, recursos(Map.of(ENCARGADO_BODEGA, 1, MONTACARGAS, 1)),
            EMPAQUE, recursos(Map.of(ENCARGADO_BODEGA, 1, ESTACION_EMPAQUE, 1)),
            CALIDAD, recursos(Map.of(ENCARGADO_BODEGA, 1, ESTACION_CALIDAD, 1)),
            DESPACHO, recursos(Map.of(ENCARGADO_BODEGA, 1, MONTACARGAS, 1, AREA_CARGA, 1, ESCANER, 1))));

    private final String nombre;
    private final String descripcion;
    private final Map<EtapaPedido, RequisitosRecursos> requisitosPorEtapa;

    TipoMercancia(String nombre, String descripcion, Map<EtapaPedido, RequisitosRecursos> requisitosPorEtapa) {
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.requisitosPorEtapa = Map.copyOf(requisitosPorEtapa);
    }

    public String nombre() { return nombre; }
    public String descripcion() { return descripcion; }
    public Map<EtapaPedido, RequisitosRecursos> requisitosPorEtapa() { return requisitosPorEtapa; }

    private static RequisitosRecursos recursos(Map<TipoRecurso, Integer> cantidades) {
        return new RequisitosRecursos(cantidades);
    }
}
