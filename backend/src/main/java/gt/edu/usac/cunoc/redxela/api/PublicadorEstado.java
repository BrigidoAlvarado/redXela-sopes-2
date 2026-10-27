package gt.edu.usac.cunoc.redxela.api;

import gt.edu.usac.cunoc.redxela.conflicto.DemostracionDeadlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class PublicadorEstado {
    private static final Logger LOG = LoggerFactory.getLogger(PublicadorEstado.class);
    private final SimpMessagingTemplate mensajes;
    private final ApiController api;
    public PublicadorEstado(SimpMessagingTemplate mensajes, ApiController api) { this.mensajes = mensajes; this.api = api; }
    public record Conteos(long admitidos, long completados, long procesando, long esperandoInventario, long esperandoRecursos, long errores) { }
    public record Estado(ApiController.EstadoSimulacionVista simulacion, Conteos pedidos,
                         List<ApiController.Recurso> recursos, DemostracionDeadlock.Vista deadlock) { }
    @Scheduled(fixedDelay = 1000)
    public void publicar() {
        try {
            var m = api.metricas();
            mensajes.convertAndSend("/topic/estado", new Estado(api.simulacion(),
                    new Conteos(m.admitidos(), m.completados(), m.procesando(), m.esperandoInventario(), m.esperandoRecursos(), m.errores()),
                    api.recursos(), api.deadlock()));
        } catch (Exception ex) { LOG.warn("No se pudo publicar la instantánea", ex); }
    }
}
