package gt.edu.usac.cunoc.redxela.dominio;

/** Validaciones del dominio, independientes de HTTP y de Spring. */
public final class Validaciones {
    private Validaciones() { }

    public static <T> T requerido(T valor, String campo) {
        if (valor == null) {
            throw new IllegalArgumentException(campo + " es obligatorio");
        }
        return valor;
    }

    public static String texto(String valor, String campo, int maximo) {
        String limpio = requerido(valor, campo).strip();
        if (limpio.isEmpty() || limpio.length() > maximo) {
            throw new IllegalArgumentException(campo + " debe tener entre 1 y " + maximo + " caracteres");
        }
        return limpio;
    }
}
