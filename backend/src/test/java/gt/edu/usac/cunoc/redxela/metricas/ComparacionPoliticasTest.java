package gt.edu.usac.cunoc.redxela.metricas;

import gt.edu.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ComparacionPoliticasTest {
    @Test void ejecutaMismaCargaEnInstanciasAisladasYAtribuyeResultados() throws Exception {
        var comparacion = new ComparacionPoliticas().comparar(Duration.ofSeconds(60), 100);
        assertEquals(6, comparacion.escenario().size());
        assertEquals(EnumSet.allOf(PoliticaPlanificacion.class), comparacion.resultados().stream()
                .map(ComparacionPoliticas.Resultado::politica).collect(Collectors.toSet()));
        assertEquals(3, comparacion.resultados().size());
        for (var resultado : comparacion.resultados()) {
            assertEquals(6, resultado.metricas().admitidos());
            assertEquals(6, resultado.metricas().completados() + resultado.pendientes());
            assertEquals(1, resultado.metricas().corrida());
            assertTrue(resultado.metricas().recursos().stream().allMatch(u -> u.porcentaje() >= 0 && u.porcentaje() <= 100));
            System.out.printf("%s completados=%d espera=%s cumplimiento=%.1f%% pendientes=%d%n", resultado.politica(),
                    resultado.metricas().completados(), resultado.metricas().esperaPromedio(), resultado.metricas().cumplimiento(), resultado.pendientes());
        }
        assertFalse(comparacion.mejores().get("completados (mayor)").isEmpty());
        assertFalse(comparacion.mejores().get("pendientes (menor)").isEmpty());
    }
}
