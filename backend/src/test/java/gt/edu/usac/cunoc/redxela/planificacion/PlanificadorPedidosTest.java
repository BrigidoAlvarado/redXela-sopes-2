package gt.edu.usac.cunoc.redxela.planificacion;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.clientes.Cliente;
import gt.edu.usac.cunoc.redxela.pedidos.LineaPedido;
import gt.edu.usac.cunoc.redxela.pedidos.Pedido;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static gt.edu.usac.cunoc.redxela.planificacion.PoliticaPlanificacion.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanificadorPedidosTest {
    private final CatalogoProductos catalogo = new CatalogoProductos();
    private final Cliente cliente = new Cliente(UUID.randomUUID(), "Ana", "ana@ejemplo.com", NivelServicio.ESTANDAR);
    private final Duration intervalo = Duration.ofMinutes(5);

    private PlanificadorPedidos.Candidato candidato(NivelServicio nivel, int unidades, long secuencia, long esperaMinutos) {
        var pedido = new Pedido(UUID.randomUUID(), cliente, nivel,
                List.of(new LineaPedido(catalogo.buscar("GEN-01"), unidades)), secuencia);
        return new PlanificadorPedidos.Candidato(pedido, Duration.ofMinutes(esperaMinutos).toNanos());
    }

    @Test void prioridadEstrictaDesempataPorSecuenciaSinEnvejecer() {
        var programado = candidato(NivelServicio.PROGRAMADO, 1, 1, 200);
        var expresSegundo = candidato(NivelServicio.EXPRES, 10, 3, 0);
        var expresPrimero = candidato(NivelServicio.EXPRES, 20, 2, 0);
        var orden = List.of(programado, expresSegundo, expresPrimero).stream()
                .sorted(PlanificadorPedidos.comparador(PRIORIDAD, intervalo)).toList();
        assertEquals(List.of(expresPrimero, expresSegundo, programado), orden);
    }

    @Test void sjfOrdenaPorUnidadesYDesempataPorSecuencia() {
        var veinte = candidato(NivelServicio.EXPRES, 20, 1, 0);
        var cincoSegundo = candidato(NivelServicio.ECONOMICO, 5, 3, 0);
        var cincoPrimero = candidato(NivelServicio.PROGRAMADO, 5, 2, 0);
        var doce = candidato(NivelServicio.ESTANDAR, 12, 4, 0);
        var orden = List.of(veinte, cincoSegundo, doce, cincoPrimero).stream()
                .sorted(PlanificadorPedidos.comparador(SJF, intervalo)).toList();
        assertEquals(List.of(cincoPrimero, cincoSegundo, doce, veinte), orden);
    }

    @Test void agingRecalculaLaPrioridadYResuelveEmpatesPorSecuencia() {
        var economicoAntiguo = candidato(NivelServicio.ECONOMICO, 1, 1, 25);
        var expresNuevo = candidato(NivelServicio.EXPRES, 1, 2, 0);
        var economicoMismoPuntaje = candidato(NivelServicio.ECONOMICO, 1, 3, 25);
        assertEquals(0, PlanificadorPedidos.prioridadEfectiva(economicoAntiguo, intervalo));
        assertEquals(1, PlanificadorPedidos.prioridadEfectiva(expresNuevo, intervalo));
        assertEquals(List.of(economicoAntiguo, economicoMismoPuntaje, expresNuevo),
                List.of(expresNuevo, economicoMismoPuntaje, economicoAntiguo).stream()
                        .sorted(PlanificadorPedidos.comparador(AGING, intervalo)).toList());
        assertEquals(5, PlanificadorPedidos.prioridadEfectiva(candidato(NivelServicio.ECONOMICO, 1, 1, 4), intervalo));
        assertEquals(4, PlanificadorPedidos.prioridadEfectiva(candidato(NivelServicio.ECONOMICO, 1, 1, 5), intervalo));
    }

    @Test void validaIntervaloYPermiteCambiarlo() {
        var c = candidato(NivelServicio.ECONOMICO, 1, 1, 10);
        assertEquals(3, PlanificadorPedidos.prioridadEfectiva(c, intervalo));
        assertEquals(4, PlanificadorPedidos.prioridadEfectiva(c, Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class, () -> PlanificadorPedidos.intervaloNanos(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PlanificadorPedidos.intervaloNanos(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> PlanificadorPedidos.intervaloNanos(Duration.ofSeconds(Long.MAX_VALUE)));
    }
}
