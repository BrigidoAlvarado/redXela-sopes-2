package gt.edu.usac.cunoc.redxela.catalogo;

import gt.edu.usac.cunoc.redxela.pedidos.EtapaPedido;
import gt.edu.usac.cunoc.redxela.recursos.RequisitosRecursos;
import gt.edu.usac.cunoc.redxela.recursos.TipoRecurso;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CatalogoTest {
    private final CatalogoProductos catalogo = new CatalogoProductos();

    @Test
    void catalogoTieneIdsUnicosYCubreLosCincoTipos() {
        assertEquals(10, catalogo.listar().size());
        assertEquals(10, catalogo.listar().stream().map(Producto::id).distinct().count());
        assertEquals(Set.of(TipoMercancia.values()), catalogo.listar().stream()
                .map(Producto::tipo).collect(Collectors.toSet()));
        assertSame(catalogo.buscar("GEN-01"), catalogo.buscar(" gen-01 "));
        assertThrows(IllegalArgumentException.class, () -> catalogo.buscar("NO-EXISTE"));
        assertThrows(UnsupportedOperationException.class, () -> catalogo.listar().clear());
    }

    @Test
    void perfilesSonDistintosYSiempreIncluyenCargaYEscanerEnDespacho() {
        assertEquals(5, Arrays.stream(TipoMercancia.values())
                .map(TipoMercancia::requisitosPorEtapa).distinct().count());
        for (TipoMercancia tipo : TipoMercancia.values()) {
            var despacho = tipo.requisitosPorEtapa().get(EtapaPedido.DESPACHO);
            assertEquals(1, despacho.cantidad(TipoRecurso.ESCANER));
            assertEquals(1, despacho.cantidad(TipoRecurso.AREA_CARGA));
            assertFalse(tipo.descripcion().isBlank());
            assertThrows(UnsupportedOperationException.class, () -> tipo.requisitosPorEtapa().clear());
        }
    }

    @ParameterizedTest
    @EnumSource(TipoMercancia.class)
    void requisitosNoSuperanCapacidades(TipoMercancia tipo) {
        assertTrue(tipo.requisitosPorEtapa().containsKey(EtapaPedido.RETIRO));
        assertTrue(tipo.requisitosPorEtapa().containsKey(EtapaPedido.EMPAQUE));
        tipo.requisitosPorEtapa().values().forEach(requisitos ->
                requisitos.cantidades().forEach((recurso, cantidad) ->
                        assertTrue(cantidad > 0 && cantidad <= recurso.capacidad())));
    }

    @Test
    void capacidadesEIdentificadoresCoincidenConEnunciadoYEscenarioAprobado() {
        assertEquals(6, TipoRecurso.ESTACION_EMPAQUE.capacidad());
        assertEquals(2, TipoRecurso.AREA_CARGA.capacidad());
        assertEquals(10, TipoRecurso.ENCARGADO_BODEGA.capacidad());
        assertEquals(3, TipoRecurso.MONTACARGAS.capacidad());
        assertEquals(4, TipoRecurso.ESTACION_CALIDAD.capacidad());
        assertEquals(1, TipoRecurso.ESCANER.capacidad());
        assertEquals(26, Arrays.stream(TipoRecurso.values()).mapToInt(TipoRecurso::capacidad).sum());
        assertEquals("C01", TipoRecurso.AREA_CARGA.identificador(1));
        assertEquals("S01", TipoRecurso.ESCANER.identificador(1));
        assertEquals("B10", TipoRecurso.ENCARGADO_BODEGA.identificador(10));
        assertThrows(IllegalArgumentException.class, () -> TipoRecurso.ESCANER.identificador(2));
    }

    @Test
    void conservaObjetivosDeEsperaYRechazaNivelInvalido() {
        assertEquals(Duration.ofSeconds(30), NivelServicio.EXPRES.objetivoEspera());
        assertEquals(Duration.ofMinutes(10), NivelServicio.PRIORITARIO.objetivoEspera());
        assertEquals(Duration.ofMinutes(30), NivelServicio.ESTANDAR.objetivoEspera());
        assertEquals(Duration.ofMinutes(60), NivelServicio.PROGRAMADO.objetivoEspera());
        assertEquals(Duration.ofMinutes(120), NivelServicio.ECONOMICO.objetivoEspera());
        for (NivelServicio servicio : NivelServicio.values()) {
            assertSame(servicio, NivelServicio.desdeNivel(servicio.nivel()));
        }
        assertThrows(IllegalArgumentException.class, () -> NivelServicio.desdeNivel(0));
        assertThrows(IllegalArgumentException.class, () -> NivelServicio.desdeNivel(6));
    }

    @Test
    void requisitosSonCopiaDefensivaYRechazanCantidadesImposibles() {
        var mapa = new HashMap<TipoRecurso, Integer>();
        mapa.put(TipoRecurso.ESCANER, 1);
        var requisitos = new RequisitosRecursos(mapa);
        mapa.clear();
        assertEquals(1, requisitos.cantidad(TipoRecurso.ESCANER));
        assertThrows(UnsupportedOperationException.class, () -> requisitos.cantidades().clear());
        assertThrows(IllegalArgumentException.class, () -> new RequisitosRecursos(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RequisitosRecursos(Map.of(TipoRecurso.ESCANER, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new RequisitosRecursos(Map.of(TipoRecurso.ESCANER, 2)));
        mapa.put(TipoRecurso.ESCANER, null);
        assertThrows(IllegalArgumentException.class, () -> new RequisitosRecursos(mapa));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "con espacios", "x@y"})
    void rechazaCodigosInvalidos(String id) {
        assertThrows(IllegalArgumentException.class, () -> catalogo.buscar(id));
    }

    @Test
    void validaDatosBasicosDelProducto() {
        assertThrows(IllegalArgumentException.class, () -> new Producto("X", " ", TipoMercancia.GENERAL, 1));
        assertThrows(IllegalArgumentException.class, () -> new Producto("X", "Producto", null, 1));
        assertThrows(IllegalArgumentException.class, () -> new Producto("X", "Producto", TipoMercancia.GENERAL, 0));
        assertThrows(IllegalArgumentException.class, () -> catalogo.buscar(null));
    }
}
