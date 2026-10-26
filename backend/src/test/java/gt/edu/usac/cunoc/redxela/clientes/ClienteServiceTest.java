package gt.edu.usac.cunoc.redxela.clientes;

import gt.edu.usac.cunoc.redxela.Concurrencia;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClienteServiceTest {
    private final ClienteService clientes = new ClienteService();

    @Test
    void registraNormalizaYConsultaSinExponerColeccionMutable() {
        var cliente = clientes.registrar(" Ana López ", " ANA@EJEMPLO.COM ", NivelServicio.PRIORITARIO);
        assertEquals("Ana López", cliente.nombre());
        assertEquals("ana@ejemplo.com", cliente.correo());
        assertSame(cliente, clientes.buscar(cliente.id()));
        assertThrows(IllegalArgumentException.class,
                () -> clientes.registrar("Otra Ana", "ana@ejemplo.com", NivelServicio.ECONOMICO));
        assertThrows(UnsupportedOperationException.class, () -> clientes.listar().clear());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "sin-arroba", "a@", "@ejemplo.com", "a@@ejemplo.com", "a b@ejemplo.com", "a@x..com"})
    void rechazaCorreoInvalido(String correo) {
        assertThrows(IllegalArgumentException.class,
                () -> clientes.registrar("Ana", correo, NivelServicio.EXPRES));
        assertTrue(clientes.listar().isEmpty());
    }

    @Test
    void validaCamposYClienteDesconocido() {
        assertThrows(IllegalArgumentException.class, () -> clientes.registrar(" ", "a@b.com", NivelServicio.EXPRES));
        assertThrows(IllegalArgumentException.class, () -> clientes.registrar("Ana", "a@b.com", null));
        assertThrows(IllegalArgumentException.class, () -> clientes.buscar(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> clientes.buscar(null));
        assertTrue(clientes.listar().isEmpty());
    }

    @Test
    void registroConcurrenteDelMismoCorreoSoloAceptaUnCliente() throws Exception {
        var resultados = Concurrencia.ejecutar(64, indice -> {
            try {
                clientes.registrar("Cliente " + indice, indice % 2 == 0 ? " ANA@EJEMPLO.COM " : "ana@ejemplo.com",
                        NivelServicio.ESTANDAR);
                return true;
            } catch (IllegalArgumentException duplicado) {
                assertEquals("Ya existe un cliente con ese correo", duplicado.getMessage());
                return false;
            }
        });
        assertEquals(1, resultados.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, clientes.listar().size());
        var cliente = clientes.listar().getFirst();
        assertEquals(cliente, clientes.buscar(cliente.id()));
    }

    @Test
    void registrosConcurrentesDistintosNoSePierden() throws Exception {
        var resultados = Concurrencia.ejecutar(64, indice -> {
            var cliente = clientes.registrar("Cliente " + indice, "cliente" + indice + "@ejemplo.com", NivelServicio.EXPRES);
            assertEquals(cliente, clientes.buscar(cliente.id()));
            return cliente;
        });
        assertEquals(64, clientes.listar().size());
        assertEquals(64, resultados.stream().map(Cliente::id).distinct().count());
    }
}
