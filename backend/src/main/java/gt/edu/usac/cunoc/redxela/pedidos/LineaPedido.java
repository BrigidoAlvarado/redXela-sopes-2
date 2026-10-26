package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.catalogo.Producto;

import static gt.edu.usac.cunoc.redxela.dominio.Validaciones.requerido;

public record LineaPedido(Producto producto, int cantidad) {
    public LineaPedido {
        requerido(producto, "Producto");
        if (cantidad < 1) {
            throw new IllegalArgumentException("La cantidad solicitada debe ser positiva");
        }
    }

    public long volumenTotal() { return (long) cantidad * producto.volumenPorUnidad(); }
}
