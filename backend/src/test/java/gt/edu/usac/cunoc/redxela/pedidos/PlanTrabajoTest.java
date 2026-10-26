package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.catalogo.TipoMercancia;
import gt.edu.usac.cunoc.redxela.clientes.Cliente;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlanTrabajoTest {
    private final CatalogoProductos catalogo = new CatalogoProductos();
    private final Cliente cliente = new Cliente(UUID.randomUUID(), "Ana", "ana@ejemplo.com", NivelServicio.EXPRES);

    @Test
    void pedidoMixtoAgrupaProductosDelMismoTipoSinDuplicarEtapas() {
        var pedido = new Pedido(UUID.randomUUID(), cliente, NivelServicio.EXPRES, List.of(
                new LineaPedido(catalogo.buscar("GEN-01"), 2),
                new LineaPedido(catalogo.buscar("GEN-02"), 3),
                new LineaPedido(catalogo.buscar("FRA-01"), 4)), 1);
        var plan = PlanTrabajo.paraPedido(pedido);
        assertEquals(pedido.id(), plan.pedidoId());
        assertEquals(7, plan.pasos().size()); // General: 3 etapas; Frágil: 4 etapas.
        assertEquals(List.of(EtapaPedido.RETIRO, EtapaPedido.RETIRO, EtapaPedido.EMPAQUE, EtapaPedido.EMPAQUE,
                EtapaPedido.CALIDAD, EtapaPedido.DESPACHO, EtapaPedido.DESPACHO),
                plan.pasos().stream().map(PasoTrabajo::etapa).toList());
        assertEquals(1, plan.pasos().stream().filter(paso -> paso.etapa() == EtapaPedido.CALIDAD).count());
        for (int i = 0; i < plan.pasos().size(); i++) {
            var paso = plan.pasos().get(i);
            assertEquals(i, paso.posicion());
            assertEquals(paso.tipo() == TipoMercancia.GENERAL ? 5 : 4, paso.unidades());
            assertNotNull(paso.requisitos());
        }
        assertEquals(plan.pasos(), PlanTrabajo.paraPedido(pedido).pasos());
        assertThrows(UnsupportedOperationException.class, () -> plan.pasos().clear());
    }

    @Test
    void masUnidadesAumentanCargaDeCadaPasoSinMultiplicarRecursos() {
        var pequeno = new Pedido(UUID.randomUUID(), cliente, NivelServicio.EXPRES,
                List.of(new LineaPedido(catalogo.buscar("VOL-01"), 1)), 1);
        var grande = new Pedido(UUID.randomUUID(), cliente, NivelServicio.EXPRES,
                List.of(new LineaPedido(catalogo.buscar("VOL-01"), 20)), 2);
        var planPequeno = PlanTrabajo.paraPedido(pequeno);
        var planGrande = PlanTrabajo.paraPedido(grande);
        for (int i = 0; i < planPequeno.pasos().size(); i++) {
            assertEquals(20 * planPequeno.pasos().get(i).unidades(), planGrande.pasos().get(i).unidades());
            assertEquals(planPequeno.pasos().get(i).requisitos(), planGrande.pasos().get(i).requisitos());
        }
    }

    @Test
    void noAceptaPasoSinPerfilOUnidadesNiPedidoNulo() {
        assertThrows(IllegalArgumentException.class, () -> PlanTrabajo.paraPedido(null));
        assertThrows(IllegalArgumentException.class, () -> new PasoTrabajo(0, EtapaPedido.CALIDAD, TipoMercancia.GENERAL, 1));
        assertThrows(IllegalArgumentException.class, () -> new PasoTrabajo(-1, EtapaPedido.RETIRO, TipoMercancia.GENERAL, 1));
        assertThrows(IllegalArgumentException.class, () -> new PasoTrabajo(0, EtapaPedido.RETIRO, TipoMercancia.GENERAL, 0));
    }
}
