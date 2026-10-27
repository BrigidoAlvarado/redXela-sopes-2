package gt.edu.usac.cunoc.redxela.simulacion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RelojSimuladoTest {
    private final AtomicLong nanosReales = new AtomicLong(123_000);
    private final RelojSimulado reloj = new RelojSimulado(60, nanosReales::get);

    @Test
    void comienzaCongeladoYUnSegundoRealEquivaleASesentaSimulados() {
        avanzarSegundosReales(10);
        assertEquals(0, reloj.tiempoActual());
        reloj.iniciar();
        avanzarSegundosReales(1);
        assertEquals(Duration.ofSeconds(60).toNanos(), reloj.tiempoActual());
    }

    @Test
    void pausaYReanudacionNoCobranTiempoPausadoNiSaltanAlRepetirse() {
        reloj.iniciar();
        avanzarSegundosReales(2);
        reloj.pausar();
        avanzarSegundosReales(100);
        reloj.pausar();
        assertEquals(Duration.ofSeconds(120).toNanos(), reloj.tiempoActual());
        reloj.reanudar();
        reloj.reanudar();
        assertEquals(Duration.ofSeconds(120).toNanos(), reloj.tiempoActual());
        avanzarSegundosReales(1);
        assertEquals(Duration.ofSeconds(180).toNanos(), reloj.tiempoActual());
    }

    @Test
    void cambioDeVelocidadPreservaLoAcumuladoConElFactorAnterior() {
        reloj.iniciar();
        avanzarSegundosReales(1);
        reloj.cambiarVelocidad(10);
        assertEquals(Duration.ofSeconds(60).toNanos(), reloj.tiempoActual());
        avanzarSegundosReales(2);
        assertEquals(Duration.ofSeconds(80).toNanos(), reloj.tiempoActual());
        reloj.pausar();
        avanzarSegundosReales(50);
        reloj.cambiarVelocidad(0.5);
        assertEquals(Duration.ofSeconds(80).toNanos(), reloj.tiempoActual());
        reloj.reanudar();
        avanzarSegundosReales(2);
        assertEquals(Duration.ofSeconds(81).toNanos(), reloj.tiempoActual());
    }

    @Test
    void consultasFrecuentesConservanFraccionesDeNanosegundo() {
        var lento = new RelojSimulado(0.25, nanosReales::get);
        lento.iniciar();
        for (int i = 0; i < 4; i++) {
            nanosReales.incrementAndGet();
            lento.tiempoActual();
        }
        assertEquals(1, lento.tiempoActual());
    }

    @Test
    void nuevaCorridaReiniciaSoloElAcumuladoYConservaLaVelocidad() {
        reloj.iniciar();
        avanzarSegundosReales(3);
        reloj.pausar();
        reloj.cambiarVelocidad(5);
        reloj.iniciar();
        assertEquals(0, reloj.tiempoActual());
        avanzarSegundosReales(1);
        assertEquals(Duration.ofSeconds(5).toNanos(), reloj.tiempoActual());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rechazaFactoresInvalidosSinAlterarConfiguracion(double factor) {
        assertThrows(IllegalArgumentException.class, () -> new RelojSimulado(factor));
        assertThrows(IllegalArgumentException.class, () -> reloj.cambiarVelocidad(factor));
        assertEquals(60, reloj.factor());
    }

    private void avanzarSegundosReales(long segundos) {
        nanosReales.addAndGet(Duration.ofSeconds(segundos).toNanos());
    }
}
