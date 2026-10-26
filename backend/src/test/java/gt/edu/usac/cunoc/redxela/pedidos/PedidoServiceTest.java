package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.Concurrencia;
import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.clientes.Cliente;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PedidoServiceTest {
    private final CatalogoProductos catalogo = new CatalogoProductos();
    private final ClienteService clientes = new ClienteService();
    private final Cliente cliente = clientes.registrar("Ana", "ana@ejemplo.com", NivelServicio.EXPRES);
    private final PedidoService pedidos = new PedidoService(catalogo, clientes);

    @Test
    void creaPedidoMixtoConServicioContratadoIndependienteDelPreferido() {
        var pedido = pedidos.crear(cliente.id(), NivelServicio.ECONOMICO,
                List.of(new SolicitudLineaPedido("GEN-01", 3), new SolicitudLineaPedido("FRA-01", 2)));
        assertEquals(5, pedido.unidadesTotales());
        assertEquals(EstadoPedido.CREADO, pedido.estado());
        assertEquals(NivelServicio.ECONOMICO, pedido.nivelServicio());
        assertEquals(NivelServicio.EXPRES, pedido.cliente().servicioPreferido());
        assertEquals(pedido, pedidos.buscar(pedido.id()));
        assertSame(catalogo.buscar("GEN-01"), pedido.lineas().getFirst().producto());
    }

    @Test
    void noAceptaProductosNiClientesDesconocidosNiLineasDuplicadas() {
        assertThrows(IllegalArgumentException.class, () -> pedidos.crear(UUID.randomUUID(), NivelServicio.EXPRES,
                List.of(new SolicitudLineaPedido("GEN-01", 1))));
        assertThrows(IllegalArgumentException.class, () -> pedidos.crear(cliente.id(), NivelServicio.EXPRES,
                List.of(new SolicitudLineaPedido("DESCONOCIDO", 1))));
        assertThrows(IllegalArgumentException.class, () -> pedidos.crear(cliente.id(), NivelServicio.EXPRES,
                List.of(new SolicitudLineaPedido("GEN-01", 1), new SolicitudLineaPedido("gen-01", 2))));
        assertTrue(pedidos.listar().isEmpty());
    }

    @Test
    void rechazaDatosIncompletosSinRegistrarPedido() {
        assertThrows(IllegalArgumentException.class, () -> pedidos.crear(cliente.id(), NivelServicio.EXPRES, List.of()));
        assertThrows(IllegalArgumentException.class, () -> pedidos.crear(cliente.id(), NivelServicio.EXPRES, null));
        assertThrows(IllegalArgumentException.class, () -> pedidos.crear(cliente.id(), null,
                List.of(new SolicitudLineaPedido("GEN-01", 1))));
        assertThrows(IllegalArgumentException.class, () -> pedidos.crear(cliente.id(), NivelServicio.EXPRES,
                Arrays.asList((SolicitudLineaPedido) null)));
        assertThrows(IllegalArgumentException.class, () -> pedidos.buscar(UUID.randomUUID()));
        assertTrue(pedidos.listar().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rechazaCantidadesNoPositivas(int cantidad) {
        assertThrows(IllegalArgumentException.class, () -> new SolicitudLineaPedido("GEN-01", cantidad));
        assertThrows(IllegalArgumentException.class, () -> new LineaPedido(catalogo.buscar("GEN-01"), cantidad));
    }

    @Test
    void sumasYVolumenNoDesbordanInt() {
        var pedido = pedidos.crear(cliente.id(), NivelServicio.EXPRES, List.of(
                new SolicitudLineaPedido("GEN-01", Integer.MAX_VALUE),
                new SolicitudLineaPedido("VOL-01", Integer.MAX_VALUE)));
        assertEquals(4_294_967_294L, pedido.unidadesTotales());
        assertEquals(10_737_418_235L, pedido.lineas().get(1).volumenTotal());
    }

    @Test
    void coleccionesDelPedidoSonCopiasDefensivas() {
        var lineas = new ArrayList<>(List.of(new LineaPedido(catalogo.buscar("GEN-01"), 2)));
        var pedido = new Pedido(UUID.randomUUID(), cliente, NivelServicio.EXPRES, lineas, 1);
        lineas.clear();
        assertEquals(2, pedido.unidadesTotales());
        assertThrows(UnsupportedOperationException.class, () -> pedido.lineas().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new Pedido(null, cliente, NivelServicio.EXPRES, pedido.lineas(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Pedido(UUID.randomUUID(), cliente, NivelServicio.EXPRES, pedido.lineas(), 0));
    }

    @Test
    void creacionConcurrenteConservaTodosLosPedidosConSecuenciasEIdsUnicos() throws Exception {
        var creados = Concurrencia.ejecutar(128, indice -> pedidos.crear(cliente.id(), NivelServicio.ESTANDAR,
                List.of(new SolicitudLineaPedido("GEN-01", indice + 1))));
        assertEquals(128, creados.stream().map(Pedido::id).distinct().count());
        assertEquals(128, creados.stream().map(Pedido::secuenciaEntrada).distinct().count());
        assertEquals(128, pedidos.listar().size());
        for (int i = 0; i < pedidos.listar().size(); i++) {
            assertEquals(i + 1, pedidos.listar().get(i).secuenciaEntrada());
        }
        assertThrows(UnsupportedOperationException.class, () -> pedidos.listar().clear());
    }
}
