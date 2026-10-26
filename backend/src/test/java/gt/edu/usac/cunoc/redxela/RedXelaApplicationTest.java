package gt.edu.usac.cunoc.redxela;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.pedidos.PedidoService;
import gt.edu.usac.cunoc.redxela.pedidos.SolicitudLineaPedido;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RedXelaApplicationTest {
    @Autowired private CatalogoProductos catalogo;
    @Autowired private ClienteService clientes;
    @Autowired private PedidoService pedidos;

    @Test
    void springConectaElDominioYPermiteCrearUnPedidoValido() {
        assertEquals(10, catalogo.listar().size());
        var cliente = clientes.registrar("Cliente de arranque", "spring@ejemplo.com", NivelServicio.PRIORITARIO);
        var pedido = pedidos.crear(cliente.id(), cliente.servicioPreferido(), List.of(new SolicitudLineaPedido("FRA-01", 3)));
        assertEquals(3, pedidos.buscar(pedido.id()).unidadesTotales());
    }
}
