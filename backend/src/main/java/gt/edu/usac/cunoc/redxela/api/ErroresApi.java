package gt.edu.usac.cunoc.redxela.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import java.util.NoSuchElementException;
import java.util.concurrent.RejectedExecutionException;

@RestControllerAdvice
public class ErroresApi {
    private static final Logger LOG = LoggerFactory.getLogger(ErroresApi.class);
    public record Error(String codigo, String mensaje) { }
    private ResponseEntity<Error> respuesta(HttpStatus estado, String codigo, String mensaje) {
        return ResponseEntity.status(estado).body(new Error(codigo, mensaje));
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Error> inexistente(NoSuchElementException e) { return respuesta(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", e.getMessage()); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Error> rutaInexistente(NoResourceFoundException e) { return respuesta(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "Ruta no encontrada"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Error> metodoInvalido(HttpRequestMethodNotSupportedException e) { return respuesta(HttpStatus.METHOD_NOT_ALLOWED, "METODO_NO_PERMITIDO", "Método no permitido"); }
    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Error> invalido(Exception e) { return respuesta(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS", e instanceof IllegalArgumentException ? e.getMessage() : "Solicitud inválida"); }
    @ExceptionHandler(RejectedExecutionException.class)
    ResponseEntity<Error> saturado(RejectedExecutionException e) { return respuesta(HttpStatus.TOO_MANY_REQUESTS, "CAPACIDAD_AGOTADA", e.getMessage()); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Error> conflicto(IllegalStateException e) { return respuesta(HttpStatus.CONFLICT, "OPERACION_NO_PERMITIDA", e.getMessage()); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> inesperado(Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        LOG.error("Error atendiendo solicitud", e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "No se pudo completar la solicitud");
    }
}
