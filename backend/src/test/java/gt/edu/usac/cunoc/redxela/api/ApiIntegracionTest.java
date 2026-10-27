package gt.edu.usac.cunoc.redxela.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import gt.edu.usac.cunoc.redxela.RedXelaApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = RedXelaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegracionTest {
    @LocalServerPort int puerto;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void restYCorsFuncionanSinClientesWebSocket() throws Exception {
        assertEquals(26, get("/api/recursos").size());
        assertEquals(404, enviar("GET", "/api/no-existe", null).statusCode());
        var permitido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/api/simulacion"))
                .header("Origin", "http://localhost:4200").GET().build();
        var respuesta = http.send(permitido, HttpResponse.BodyHandlers.ofString());
        assertEquals("http://localhost:4200", respuesta.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var rechazado = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/api/simulacion"))
                .header("Origin", "http://ejemplo.invalid").GET().build();
        assertEquals(403, http.send(rechazado, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void flujoHttpYWebSocket() throws Exception {
        var websocketMensaje = new CountDownLatch(1);
        var textoWs = new StringBuilder();
        WebSocket ws = http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + puerto + "/ws"), new WebSocket.Listener() {
            @Override public void onOpen(WebSocket socket) {
                socket.request(1);
                socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\n\n\u0000", true);
            }
            @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                textoWs.append(data);
                if (textoWs.indexOf("CONNECTED") >= 0 && textoWs.indexOf("SUBSCRITO") < 0) {
                    textoWs.append("SUBSCRITO");
                    socket.sendText("SUBSCRIBE\nid:1\ndestination:/topic/estado\nack:auto\n\n\u0000", true);
                }
                int mensaje = textoWs.indexOf("MESSAGE");
                if (mensaje >= 0 && textoWs.indexOf("\u0000", mensaje) >= 0) websocketMensaje.countDown();
                socket.request(1);
                return CompletableFuture.completedFuture(null);
            }
        }).get(10, TimeUnit.SECONDS);
        try {
            assertEquals("DETENIDA", get("/api/simulacion").path("estado").asString());
            assertEquals(409, enviar("POST", "/api/simulacion/reanudar", null).statusCode());
            assertEquals(409, enviar("POST", "/api/pedidos", "{}").statusCode());
            assertEquals(200, enviar("POST", "/api/simulacion/iniciar", null).statusCode());
            assertEquals(200, enviar("PUT", "/api/simulacion/velocidad", "{\"factor\":10000}").statusCode());
            assertEquals(200, enviar("POST", "/api/simulacion/pausar", null).statusCode());
            assertEquals("PAUSADA", get("/api/simulacion").path("estado").asString());
            assertEquals(409, enviar("POST", "/api/pedidos", "{}").statusCode());
            assertEquals(200, enviar("POST", "/api/simulacion/reanudar", null).statusCode());
            var cliente = enviar("POST", "/api/clientes", "{\"nombre\":\"Ana\",\"correo\":\"ana@example.com\",\"servicioPreferido\":\"EXPRES\"}");
            assertEquals(201, cliente.statusCode()); String clienteId = json.readTree(cliente.body()).path("id").asString();
            assertEquals(clienteId, get("/api/clientes/" + clienteId).path("id").asString());
            assertTrue(get("/api/clientes").isArray()); assertEquals(10, get("/api/catalogo").size());
            assertEquals(400, enviar("POST", "/api/pedidos", "{\"clienteId\":\"" + clienteId + "\",\"nivelServicio\":\"EXPRES\",\"lineas\":[{\"productoId\":\"GEN-01\",\"cantidad\":0}]}").statusCode());
            var cuerpo = "{\"clienteId\":\"" + clienteId + "\",\"nivelServicio\":\"EXPRES\",\"lineas\":[{\"productoId\":\"GEN-01\",\"cantidad\":1}]}";
            var a = enviar("POST", "/api/pedidos", cuerpo); var b = enviar("POST", "/api/pedidos", cuerpo);
            assertEquals(201, a.statusCode()); assertEquals(201, b.statusCode());
            String idA = json.readTree(a.body()).path("pedido").path("id").asString();
            String idB = json.readTree(b.body()).path("pedido").path("id").asString();
            assertEquals("ESPERANDO_INVENTARIO", get("/api/pedidos/" + idA).path("progreso").path("estado").asString());
            assertTrue(get("/api/pedidos").isArray()); assertTrue(get("/api/pedidos/cola").isArray());
            assertEquals(200, enviar("PUT", "/api/planificacion", "{\"politica\":\"SJF\",\"intervaloAgingMillis\":300000}").statusCode());
            assertEquals("SJF", get("/api/planificacion").path("politica").asString());
            assertEquals(200, enviar("PUT", "/api/generador/intervalo", "{\"intervaloMillis\":3600000}").statusCode());
            assertTrue(json.readTree(enviar("POST", "/api/generador/iniciar", null).body()).path("activo").asBoolean());
            enviar("POST", "/api/generador/detener", null);
            assertFalse(get("/api/generador").path("activo").asBoolean());
            assertEquals(26, get("/api/recursos").size());
            assertEquals(10, get("/api/inventario").size()); assertEquals(100, get("/api/inventario/ubicaciones").size());
            assertEquals("INACTIVA", get("/api/deadlock").path("estado").asString());
            assertEquals(200, enviar("POST", "/api/deadlock/iniciar", "{\"pedidoA\":\"" + idA + "\",\"pedidoB\":\"" + idB + "\"}").statusCode());
            var ingreso = enviar("POST", "/api/recepciones", "{\"productoId\":\"GEN-01\",\"cantidad\":2}");
            assertEquals(202, ingreso.statusCode()); String ingresoId = json.readTree(ingreso.body()).path("ingreso").path("id").asString();
            await().atMost(Duration.ofSeconds(10)).until(() -> get("/api/recepciones/" + ingresoId).path("estado").asString().equals("ALMACENADA"));
            await().atMost(Duration.ofSeconds(10)).until(() -> get("/api/deadlock").path("deadlock").asBoolean());
            assertEquals(200, enviar("POST", "/api/deadlock/resolver", "{\"pedidoGanador\":\"" + idA + "\"}").statusCode());
            await().atMost(Duration.ofSeconds(10)).until(() -> get("/api/deadlock").path("estado").asString().equals("RESUELTA"));
            await().atMost(Duration.ofSeconds(10)).until(() -> get("/api/metricas").path("completados").asInt() == 2);
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                var unidades = get("/api/recursos");
                for (var unidad : unidades) if (!unidad.path("disponible").asBoolean()) return false;
                return true;
            });
            var m = get("/api/metricas");
            assertTrue(m.path("tiempoObservadoMillis").isNumber()); assertEquals(2, m.path("admitidos").asInt());
            var comparacion = enviar("POST", "/api/comparaciones", "{\"observacionMillis\":60000,\"factor\":100}");
            assertEquals(202, comparacion.statusCode()); String comparacionId = json.readTree(comparacion.body()).path("id").asString();
            assertEquals(429, enviar("POST", "/api/comparaciones", "{\"observacionMillis\":60000,\"factor\":100}").statusCode());
            await().atMost(Duration.ofSeconds(15)).until(() -> get("/api/comparaciones/" + comparacionId).path("estado").asString().equals("COMPLETADA"));
            assertEquals(3, get("/api/comparaciones/" + comparacionId).path("resultado").path("resultados").size());
            assertEquals(404, enviar("GET", "/api/clientes/00000000-0000-0000-0000-000000000000", null).statusCode());
            assertEquals("DATOS_INVALIDOS", json.readTree(enviar("PUT", "/api/planificacion", "{\"politica\":\"INVALIDA\"}").body()).path("codigo").asString());
            assertTrue(websocketMensaje.await(10, TimeUnit.SECONDS), "No llegó un snapshot STOMP");
            String trama = textoWs.substring(textoWs.indexOf("MESSAGE"));
            String carga = trama.substring(trama.indexOf("\n\n") + 2, trama.indexOf('\u0000'));
            var snapshot = json.readTree(carga);
            assertTrue(snapshot.path("simulacion").path("tiempoSimuladoMillis").isNumber());
            assertEquals(26, snapshot.path("recursos").size());
            assertTrue(snapshot.path("pedidos").path("admitidos").isNumber());
            assertEquals(200, enviar("POST", "/api/simulacion/detener", null).statusCode());
        } finally { ws.sendClose(WebSocket.NORMAL_CLOSURE, "fin").join(); }
    }
    private JsonNode get(String ruta) throws Exception {
        var respuesta = enviar("GET", ruta, null);
        assertEquals(200, respuesta.statusCode(), respuesta.body());
        return json.readTree(respuesta.body());
    }
    private HttpResponse<String> enviar(String metodo, String ruta, String cuerpo) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + ruta)).timeout(Duration.ofSeconds(10));
        if (cuerpo != null) builder.header("Content-Type", "application/json");
        builder.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
