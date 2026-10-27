package gt.edu.usac.cunoc.redxela.configuracion;

import gt.edu.usac.cunoc.redxela.pedidos.PedidoService;
import gt.edu.usac.cunoc.redxela.pedidos.ProcesadorPedidos;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProcesamientoConfiguration {
    @Bean(destroyMethod = "close")
    ProcesadorPedidos procesadorPedidos(PedidoService pedidos, MotorSimulacion motor, GestorRecursos recursos) {
        return new ProcesadorPedidos(pedidos, motor, recursos);
    }
}
