package gt.edu.usac.cunoc.redxela.catalogo;

import java.util.Locale;

import static gt.edu.usac.cunoc.redxela.dominio.Validaciones.*;

public record Producto(String id, String nombre, TipoMercancia tipo, int volumenPorUnidad) {
    public Producto {
        id = normalizarId(id);
        nombre = texto(nombre, "Nombre del producto", 120);
        requerido(tipo, "Tipo de mercancía");
        if (volumenPorUnidad < 1) {
            throw new IllegalArgumentException("El volumen por unidad debe ser positivo");
        }
    }

    public static String normalizarId(String id) {
        String limpio = texto(id, "ID del producto", 40).toUpperCase(Locale.ROOT);
        if (!limpio.matches("[A-Z0-9][A-Z0-9-]*")) {
            throw new IllegalArgumentException("ID del producto inválido");
        }
        return limpio;
    }
}
