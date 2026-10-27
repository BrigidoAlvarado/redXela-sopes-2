package gt.edu.usac.cunoc.redxela.api;

import gt.edu.usac.cunoc.redxela.metricas.ComparacionPoliticas;
import gt.edu.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/comparaciones")
public class ComparacionesController {
    private static final Logger LOG = LoggerFactory.getLogger(ComparacionesController.class);
    private final ComparacionPoliticas comparador;
    private final ExecutorService ejecutor = Executors.newSingleThreadExecutor();
    private final Map<UUID, Vista> resultados = new LinkedHashMap<>();
    private boolean activa;
    public ComparacionesController(ComparacionPoliticas comparador) { this.comparador = comparador; }
    public record Resultado(PoliticaPlanificacion politica, ContratosApi.Metricas metricas, long pendientes) { }
    public record Comparacion(List<ComparacionPoliticas.PedidoEscenario> escenario, long observacionMillis, double factor,
                              List<Resultado> resultados, Map<String, List<PoliticaPlanificacion>> mejores) { }
    public record Vista(UUID id, String estado, Comparacion resultado, String error) { }

    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public synchronized Vista iniciar(@RequestBody ContratosApi.ComparacionSolicitud s) {
        if (s.observacionMillis() <= 0 || s.observacionMillis() > Duration.ofHours(24).toMillis()
                || !Double.isFinite(s.factor()) || s.factor() <= 0 || s.factor() > 1_000_000
                || s.observacionMillis() / s.factor() > 5000)
            throw new IllegalArgumentException("Observación o factor fuera de límites; máximo 5 segundos reales por política");
        if (activa) throw new RejectedExecutionException("Ya existe una comparación en ejecución");
        while (resultados.size() >= 20) resultados.remove(resultados.keySet().iterator().next());
        UUID id = UUID.randomUUID();
        Vista inicial = new Vista(id, "EN_CURSO", null, null);
        resultados.put(id, inicial); activa = true;
        try { ejecutor.submit(() -> ejecutar(id, s)); }
        catch (RejectedExecutionException ex) { resultados.remove(id); activa = false; throw ex; }
        return inicial;
    }
    private void ejecutar(UUID id, ContratosApi.ComparacionSolicitud s) {
        try {
            var c = comparador.comparar(Duration.ofMillis(s.observacionMillis()), s.factor());
            var vista = new Comparacion(c.escenario(), c.observacion().toMillis(), c.factor(), c.resultados().stream()
                    .map(r -> new Resultado(r.politica(), ContratosApi.metricas(r.metricas()), r.pendientes())).toList(), c.mejores());
            synchronized (this) { resultados.put(id, new Vista(id, "COMPLETADA", vista, null)); }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Falló la comparación {}", id, ex);
            synchronized (this) { resultados.put(id, new Vista(id, "ERROR", null, "No se pudo completar la comparación")); }
        } finally { synchronized (this) { activa = false; } }
    }
    @GetMapping("/{id}") public synchronized Vista consultar(@PathVariable UUID id) {
        var vista = resultados.get(id);
        if (vista == null) throw new NoSuchElementException("Comparación no registrada");
        return vista;
    }
    @PreDestroy public void cerrar() {
        ejecutor.shutdownNow();
        try {
            if (!ejecutor.awaitTermination(5, TimeUnit.SECONDS)) LOG.warn("La comparación no terminó durante el cierre");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
