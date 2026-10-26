package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static gt.edu.usac.cunoc.redxela.dominio.Validaciones.requerido;

/** Creación y consulta; todavía no planifica, reserva inventario ni ejecuta pedidos. */
public final class PedidoService {
    private final CatalogoProductos catalogo;
    private final ClienteService clientes;
    private final AtomicLong secuencia = new AtomicLong();
    private final ConcurrentMap<UUID, Pedido> pedidos = new ConcurrentHashMap<>();

    public PedidoService(CatalogoProductos catalogo, ClienteService clientes) {
        this.catalogo = requerido(catalogo, "Catálogo");
        this.clientes = requerido(clientes, "Registro de clientes");
    }

    public Pedido crear(UUID clienteId, NivelServicio nivel, List<SolicitudLineaPedido> solicitudes) {
        var cliente = clientes.buscar(clienteId);
        requerido(nivel, "Nivel de servicio");
        requerido(solicitudes, "Líneas solicitadas");
        List<LineaPedido> lineas = solicitudes.stream().map(solicitud -> {
            requerido(solicitud, "Línea solicitada");
            return new LineaPedido(catalogo.buscar(solicitud.productoId()), solicitud.cantidad());
        }).toList();
        // La secuencia define un desempate total, no es un tiempo de llegada simulado.
        Pedido pedido = new Pedido(UUID.randomUUID(), cliente, nivel, lineas, secuencia.incrementAndGet());
        if (pedidos.putIfAbsent(pedido.id(), pedido) != null) {
            throw new IllegalStateException("Colisión de identificador de pedido");
        }
        return pedido;
    }

    public Pedido buscar(UUID id) {
        Pedido pedido = pedidos.get(requerido(id, "ID del pedido"));
        if (pedido == null) {
            throw new IllegalArgumentException("Pedido no registrado: " + id);
        }
        return pedido;
    }

    public List<Pedido> listar() {
        return pedidos.values().stream().sorted(Comparator.comparingLong(Pedido::secuenciaEntrada)).toList();
    }
}
