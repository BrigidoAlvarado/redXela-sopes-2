package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.catalogo.Producto;

/** Entrada por código: el consumidor no puede introducir un producto ajeno al catálogo. */
public record SolicitudLineaPedido(String productoId, int cantidad) {
    public SolicitudLineaPedido {
        productoId = Producto.normalizarId(productoId);
        if (cantidad < 1) {
            throw new IllegalArgumentException("La cantidad solicitada debe ser positiva");
        }
    }
}
