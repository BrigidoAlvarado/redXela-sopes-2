package gt.edu.usac.cunoc.redxela.configuracion;

import gt.edu.usac.cunoc.redxela.pedidos.PedidoService;
import gt.edu.usac.cunoc.redxela.pedidos.ProcesadorPedidos;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.inventario.InventarioService;
import gt.edu.usac.cunoc.redxela.recepcion.RecepcionService;

@Configuration(proxyBeanMethods = false)
public class ProcesamientoConfiguration {
    @Bean(destroyMethod = "close")
    ProcesadorPedidos procesadorPedidos(PedidoService pedidos, MotorSimulacion motor, GestorRecursos recursos, InventarioService inventario) {
        return new ProcesadorPedidos(pedidos, motor, recursos, inventario);
    }

    @Bean InventarioService inventarioService(CatalogoProductos catalogo) { return new InventarioService(catalogo); }

    @Bean(destroyMethod = "close")
    RecepcionService recepcionService(CatalogoProductos catalogo, InventarioService inventario, GestorRecursos recursos, MotorSimulacion motor) {
        return new RecepcionService(catalogo, inventario, recursos, motor, 100);
    }
}
