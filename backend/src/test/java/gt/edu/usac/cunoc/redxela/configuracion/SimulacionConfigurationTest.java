package gt.edu.usac.cunoc.redxela.configuracion;

import gt.edu.usac.cunoc.redxela.simulacion.EstadoSimulacion;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SimulacionConfigurationTest {
    @RepeatedTest(20)
    void springConfiguraElMotorDetenidoYLoCierraConElContexto() throws Exception {
        var contextoSpring = new AnnotationConfigApplicationContext();
        MotorSimulacion motor;
        try {
            contextoSpring.getEnvironment().getPropertySources().addFirst(new MapPropertySource("prueba", Map.of(
                    "redxela.simulacion.trabajadores", 1, "redxela.simulacion.factor-tiempo", 3)));
            contextoSpring.register(SimulacionConfiguration.class);
            contextoSpring.refresh();
            motor = contextoSpring.getBean(MotorSimulacion.class);
            assertEquals(EstadoSimulacion.DETENIDA, motor.estado());
            assertEquals(3, motor.factorTemporal());
            motor.iniciar();
            var dentro = new CountDownLatch(1);
            var futuro = motor.enviarTrabajo(contexto -> {
                contexto.avanzar(dentro::countDown);
                contexto.esperar(Duration.ofDays(1));
            });
            assertTrue(dentro.await(10, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> motor.enviarTrabajo(contexto -> { }));
            contextoSpring.close();
            assertEquals(EstadoSimulacion.DETENIDA, motor.estado());
            assertTrue(futuro.isCancelled());
        } finally {
            contextoSpring.close();
        }
    }
}
