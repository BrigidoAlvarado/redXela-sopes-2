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
import gt.edu.usac.cunoc.redxela.conflicto.DemostracionDeadlock;
import gt.edu.usac.cunoc.redxela.pedidos.GeneradorPedidos;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.metricas.MetricasService;
import gt.edu.usac.cunoc.redxela.metricas.ComparacionPoliticas;

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

    @Bean(destroyMethod = "close")
    GeneradorPedidos generadorPedidos(ClienteService clientes, PedidoService pedidos, ProcesadorPedidos procesador,
                                       CatalogoProductos catalogo, MotorSimulacion motor) {
        return new GeneradorPedidos(clientes, pedidos, procesador, catalogo, motor);
    }

    @Bean(destroyMethod = "close")
    DemostracionDeadlock demostracionDeadlock(MotorSimulacion motor, ProcesadorPedidos procesador, GestorRecursos recursos) {
        return new DemostracionDeadlock(motor, procesador, recursos);
    }

    @Bean MetricasService metricasService(MotorSimulacion motor, ProcesadorPedidos procesador, GestorRecursos recursos) {
        return new MetricasService(motor, procesador, recursos);
    }

    @Bean ComparacionPoliticas comparacionPoliticas() { return new ComparacionPoliticas(); }
}
