package gt.edu.usac.cunoc.redxela.clientes;

import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;

import java.util.Locale;
import java.util.UUID;

import static gt.edu.usac.cunoc.redxela.dominio.Validaciones.*;

public record Cliente(UUID id, String nombre, String correo, NivelServicio servicioPreferido) {
    public Cliente {
        requerido(id, "ID del cliente");
        nombre = texto(nombre, "Nombre del cliente", 120);
        correo = normalizarCorreo(correo);
        requerido(servicioPreferido, "Servicio preferido");
    }

    public static String normalizarCorreo(String correo) {
        String limpio = texto(correo, "Correo", 254).toLowerCase(Locale.ROOT);
        // Validación de formato para la simulación, no verificación de existencia del buzón.
        if (!limpio.matches("[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+")) {
            throw new IllegalArgumentException("Formato de correo inválido");
        }
        return limpio;
    }
}
