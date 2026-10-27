package gt.edu.usac.cunoc.redxela.configuracion;

import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SimulacionConfiguration {
    @Bean
    GestorRecursos gestorRecursos(MotorSimulacion motor) { return new GestorRecursos(motor.relojMedicion()); }

    @Bean(destroyMethod = "close")
    MotorSimulacion motorSimulacion(
            @Value("${redxela.simulacion.trabajadores:4}") int trabajadores,
            @Value("${redxela.simulacion.factor-tiempo:60}") double factorTemporal) {
        // Se crea detenido. Abrir Spring no comienza una corrida ni crea trabajadores.
        return new MotorSimulacion(trabajadores, factorTemporal);
    }
}
