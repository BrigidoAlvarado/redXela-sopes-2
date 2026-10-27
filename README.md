# REDXela

Simulador concurrente de un centro de distribución de comercio electrónico, proyecto final de Sistemas Operativos 2, CUNOC–USAC, segundo semestre de 2026. El [enunciado original](docs/Proyecto_Final_Sistemas_Operativos_2.md) es la fuente de requisitos.

El backend ejecuta pedidos por etapas con trabajadores limitados y semáforos reales. Incluye planificación por prioridad, aging y SJF; recepción e inventario con productor-consumidor; almacenamiento por ubicaciones; generador automático; métricas y comparación; y una demostración de deadlock real con recuperación manual. Angular permite operar y observar estas funciones por REST y WebSocket/STOMP.

## Requisitos previos

- JDK 21; `JAVA_HOME` debe apuntar al JDK correcto.
- Maven Wrapper incluido en `backend/`; no se necesita Maven global.
- Node.js 24 y npm. `frontend/.nvmrc` contiene la versión recomendada.
- Primera descarga: acceso a Maven Central y npm. No se requiere base de datos.
- Spring Boot 4.1.2 y Angular 21.2.0 están fijados en los manifiestos.

## Ejecutar

Desde la raíz, en una terminal:

```bash
cd backend
./mvnw spring-boot:run
```

En otra terminal:

```bash
cd frontend
npm ci
npm start
```

Abra **http://127.0.0.1:4200**. El backend escucha en **http://localhost:8080**, API `/api`, WebSocket `/ws` y canal `/topic/estado`. En Windows use `mvnw.cmd`. El motor inicia detenido, con cuatro trabajadores y factor 60; el inventario inicial está vacío.

En IntelliJ importe `backend/pom.xml` como Maven y seleccione JDK 21. Si aparece que falta `WebSocketMessageBrokerConfigurer`, use **Reload All Maven Projects**, revise la configuración de ejecución y confirme que se descargó `spring-boot-starter-websocket`; en terminal verifique `./mvnw -version` y `./mvnw spring-boot:run`.

## Pruebas y compilación

```bash
cd backend
./mvnw verify
```

```bash
cd frontend
npm ci
npm test
npm run build
```

Las pruebas backend incluyen concurrencia, integración HTTP y STOMP. Las pruebas frontend usan mocks y no necesitan servidor. Para los recorridos reales consulte el informe; no equivalen a probar cada escenario exhaustivamente.

## Repositorio y documentación

```text
backend/       Código Java, Maven y pruebas
frontend/      Aplicación Angular y pruebas
docs/          Enunciado, manuales y verificación
```

- [Manual técnico consolidado](docs/manual-tecnico/README.md), con referencias a F1–F9 y [contrato API](docs/manual-tecnico/F8.md).
- [Manual de usuario](docs/manual-usuario/README.md).
- [Guía de demostración](docs/GUIA_DEMOSTRACION.md).
- [Informe de verificación y limitaciones](docs/INFORME_VERIFICACION_FINAL.md).
- [Propuesta de arquitectura](PROPUESTA_ARQUITECTURA.md), referencia secundaria.

## Limitaciones

Los datos se mantienen en memoria y se pierden al cerrar el backend. El objetivo Exprés usa 30 segundos simulados como interpretación de «casi instantáneo». La comparación usa una carga fija equivalente y sus resultados pueden variar por concurrencia; no acredita una política universalmente superior. La interfaz selecciona para la demostración pedidos exclusivamente de mercancía general y el backend valida su elegibilidad definitiva. Recepciones y comparaciones iniciadas se recuerdan en la sesión de la interfaz; tras recargar se puede consultar una recepción por su ID completo. No hay autenticación ni persistencia.
