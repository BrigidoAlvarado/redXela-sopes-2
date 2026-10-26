package gt.edu.usac.cunoc.redxela.catalogo;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Catálogo fijo e inmutable; leerlo desde distintos hilos no requiere exclusión. */
public final class CatalogoProductos {
    private final List<Producto> productos = List.of(
            new Producto("GEN-01", "Cuaderno universitario", TipoMercancia.GENERAL, 1),
            new Producto("GEN-02", "Paquete de camisetas", TipoMercancia.GENERAL, 1),
            new Producto("FRA-01", "Juego de vasos", TipoMercancia.FRAGIL, 2),
            new Producto("FRA-02", "Lámpara de vidrio", TipoMercancia.FRAGIL, 2),
            new Producto("PES-01", "Caja de herramientas", TipoMercancia.PESADO, 3),
            new Producto("PES-02", "Saco de material", TipoMercancia.PESADO, 4),
            new Producto("VOL-01", "Silla empacada", TipoMercancia.VOLUMINOSO, 5),
            new Producto("VOL-02", "Mesa plegable", TipoMercancia.VOLUMINOSO, 5),
            new Producto("ELE-01", "Equipo de cómputo paletizado", TipoMercancia.ELECTRONICA_PALETIZADA, 4),
            new Producto("ELE-02", "Equipo de sonido paletizado", TipoMercancia.ELECTRONICA_PALETIZADA, 3));
    private final Map<String, Producto> porId = productos.stream()
            .collect(Collectors.toUnmodifiableMap(Producto::id, Function.identity()));

    public List<Producto> listar() { return productos; }

    public Producto buscar(String id) {
        Producto producto = porId.get(Producto.normalizarId(id));
        if (producto == null) {
            throw new IllegalArgumentException("Producto no pertenece al catálogo: " + id);
        }
        return producto;
    }
}
