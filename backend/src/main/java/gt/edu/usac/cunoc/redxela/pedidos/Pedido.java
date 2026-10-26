package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.clientes.Cliente;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static gt.edu.usac.cunoc.redxela.dominio.Validaciones.requerido;

/** Pedido inicial inmutable. Fases posteriores añadirán el seguimiento de ejecución por separado. */
public record Pedido(UUID id, Cliente cliente, NivelServicio nivelServicio,
                     List<LineaPedido> lineas, long secuenciaEntrada) {
    public Pedido {
        requerido(id, "ID del pedido");
        requerido(cliente, "Cliente");
        requerido(nivelServicio, "Nivel de servicio");
        requerido(lineas, "Líneas del pedido");
        if (lineas.isEmpty()) {
            throw new IllegalArgumentException("El pedido debe contener productos");
        }
        var productos = new HashSet<String>();
        for (LineaPedido linea : lineas) {
            requerido(linea, "Línea del pedido");
            if (!productos.add(linea.producto().id())) {
                throw new IllegalArgumentException("Producto repetido: " + linea.producto().id());
            }
        }
        lineas = List.copyOf(lineas);
        if (secuenciaEntrada < 1) {
            throw new IllegalArgumentException("La secuencia de entrada debe ser positiva");
        }
    }

    public EstadoPedido estado() { return EstadoPedido.CREADO; }

    /** long evita desbordar al sumar varias cantidades int válidas. No equivale a segundos. */
    public long unidadesTotales() { return lineas.stream().mapToLong(LineaPedido::cantidad).sum(); }
}
