package gt.edu.usac.cunoc.redxela.api;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.pedidos.PedidoService;
import gt.edu.usac.cunoc.redxela.pedidos.ProcesadorPedidos;
import gt.edu.usac.cunoc.redxela.pedidos.SolicitudLineaPedido;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdmisionPedidoTest {
    @Test void fallaAntesDeLaAdmisionNoDejaUnPedidoHuerfano() throws Exception {
        var catalogo = new CatalogoProductos();
        var clientes = new ClienteService();
        var pedidos = new PedidoService(catalogo, clientes);
        var procesador = mock(ProcesadorPedidos.class);
        when(procesador.procesar(any())).thenThrow(new IllegalStateException("Procesador cerrado"));
        try (var motor = new MotorSimulacion(1, 60)) {
            motor.iniciar();
            var api = new ApiController(motor, clientes, pedidos, procesador, null, null, null, null, null, null, catalogo);
            var cliente = clientes.registrar("Ana", "ana@example.com", NivelServicio.ESTANDAR);
            var solicitud = new ContratosApi.PedidoSolicitud(cliente.id(), NivelServicio.ESTANDAR, List.of(new SolicitudLineaPedido("GEN-01", 1)));
            assertThrows(IllegalStateException.class, () -> api.crearPedido(solicitud));
            assertTrue(pedidos.listar().isEmpty());
        }
    }
}
