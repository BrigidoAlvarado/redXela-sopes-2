# PROYECTO FINAL

## SISTEMAS OPERATIVOS 2

**Universidad de San Carlos de Guatemala**  
**Centro Universitario de Occidente**  
**División de Ciencias de la Ingeniería**  
**Carrera de Ciencias y Sistemas**  
**Segundo Semestre 2026**

**Curso:** Sistemas Operativos 2  
**Docente:** Ing. Otto Soto  
**Lugar y fecha:** Quetzaltenango, 13 de septiembre de 2026

---

## PLANTEAMIENTO DEL PROBLEMA

REDXela es un centro de distribución de comercio electrónico que enfrenta un desafío constante: recibir, procesar y despachar un volumen alto y variable de pedidos, compitiendo todos ellos por un conjunto limitado de estaciones de trabajo, personal y equipo. A diferencia de una tienda física, aquí no hay un límite de clientes; los pedidos se acumulan si el sistema no logra procesarlos con la suficiente rapidez, y una mala gestión de los recursos puede significar retrasos, pedidos perdidos, o peor aún, que el sistema completo quede detenido esperando recursos que nunca se liberan.

Un centro de distribución no funciona como una línea de producción única, sino como un sistema multifuncional y paralelo; es decir, múltiples pedidos se procesan al mismo tiempo, distintas áreas operan de forma independiente pero deben coordinarse entre sí, y ciertos recursos como estaciones de empaque, montacargas, personal especializado y áreas de carga se comparten y deben asignarse cuidadosamente para evitar conflictos o bloqueos.

Se solicita desarrollar un sistema que simule el funcionamiento interno de REDXela, aplicando los conceptos de planificación, sincronización, exclusión mutua, semáforos, prevención de deadlock, comunicación entre procesos y administración de almacenamiento vistos durante el curso.

---

## RECURSOS DEL CENTRO DE DISTRIBUCIÓN

El centro cuenta con un conjunto limitado de recursos, los cuales deben ser administrados cuidadosamente conforme llegan y se procesan los pedidos:

- 6 estaciones de empaque.
- 2 áreas de carga (para atender camiones).
- 10 encargados de bodega.
- 3 montacargas.
- 4 estaciones de control de calidad.
- 1 sistema de escaneo de códigos, compartido entre el área de recepción y el área de despacho.
- Un almacén con una capacidad de almacenamiento dada por el estudiante.

---

## FLUJO DE PEDIDOS Y NIVELES DE SERVICIO

Cada pedido que ingresa al sistema debe clasificarse según el nivel de servicio contratado por el cliente. Si el cliente es nuevo, debe poder crear una cuenta y seleccionar el tipo de servicio que desea recibir. Esto determinará qué tan pronto debe ser procesado en relación con los demás pedidos en espera.

| Nivel | Tipo de envío | Tiempo objetivo de procesamiento |
|---|---|---|
| 1 | Exprés | Procesamiento casi instantáneo |
| 2 | Prioritario | Máximo 10 minutos en espera |
| 3 | Estándar | Máximo 30 minutos en espera |
| 4 | Programado | Máximo 60 minutos en espera |
| 5 | Económico | Máximo 120 minutos en espera |

Cada pedido debe trabajar como mínimo la siguiente información:

- ID del pedido, que permita identificarlo de forma única.
- Cliente asociado a la cuenta que realizó el pedido.
- Nivel de servicio.
- Producto o productos solicitados y el tipo de mercancía asociado a cada uno.
- Cantidad de unidades solicitadas de cada producto y cantidad total de artículos del pedido.
- Estado actual del pedido.

> **NOTA:** Hay más elementos adicionales que se deben trabajar, pero será decisión del desarrollador definirlos.

El sistema debe contar con un generador automático de pedidos que simule la llegada continua de clientes de todos los niveles, sin límite alguno en su cantidad, con el fin de demostrar el funcionamiento del sistema. Adicionalmente, la interfaz debe permitir el ingreso manual de un pedido específico en cualquier momento, de forma que sea posible provocar deliberadamente algunas de las situaciones descritas más adelante.

Un aspecto que el sistema debe resolver es cómo decidir, entre todos los pedidos que compiten, cuál se procesa primero. REDXela quiere poder evaluar distintas políticas de atención, por ejemplo:

- Dar siempre prioridad estricta al nivel de servicio.
- Dar también oportunidad a pedidos que llevan más tiempo esperando.
- Procesar primero los pedidos con menor cantidad de artículos para liberar estaciones más rápido.

El sistema deberá permitir comparar el desempeño de cada política antes de decidir cuál usar en producción.

Para efectos de la simulación, la cantidad de unidades que contiene un pedido deberá utilizarse como la estimación base de la cantidad de trabajo que debe realizar el sistema. Es decir, un pedido con más unidades requiere más tiempo de uso de los recursos que uno con pocas unidades.

---

## TIPOS DE MERCANCÍA

Además del nivel de servicio, cada pedido deberá indicar el tipo de mercancía. Esta clasificación es independiente del nivel de servicio:

- El **nivel de servicio** determina principalmente cuándo debe ser atendido un pedido.
- El **tipo de mercancía** determina qué recursos necesita un pedido para ser procesado.

Cuando un pedido no puede obtener de inmediato los recursos que necesita, debe pasar a una lista de espera.

Los tipos de mercancía deberán ser definidos por el desarrollador como parte del diseño de la solución. Se deberán implementar como mínimo cinco tipos diferentes de mercancía, por ejemplo frágil, pesado, entre otras.

Cada tipo de mercancía deberá especificar, como mínimo:

- Nombre y una descripción de sus características.
- Los recursos que puede requerir durante su procesamiento.

Queda a criterio del desarrollador definir las combinaciones de los recursos para cada tipo de mercadería, siempre que se cumplan las siguientes condiciones:

- Cada tipo de mercancía debe requerir una combinación distinta de recursos solicitados.
- Debe existir al menos un escenario realista en el que dos pedidos distintos necesiten los mismos dos recursos compartidos, en momentos cercanos, para que el sistema deba resolver correctamente esa contención.

---

## COORDINACIÓN ENTRE ÁREAS OPERATIVAS

REDXela opera con al menos dos áreas que funcionan de forma independiente pero que deben mantenerse constantemente coordinadas:

- **Recepción de Mercadería:** donde llegan los camiones con nuevo stock.
- **Control de Inventario:** encargada de registrar qué productos se encuentran disponibles y en qué cantidad para poder completar los pedidos.

Estas dos áreas no deben modificar directamente las estructuras internas de la otra. En su lugar, deberán comunicarse enviando información de forma ordenada.

Por ejemplo, Recepción informará qué mercadería llegó y en qué cantidad, mientras que Inventario procesará esa información a su propio ritmo. Esta comunicación deberá funcionar correctamente incluso cuando Recepción genere información más rápido de lo que Inventario puede procesarla, evitando la pérdida de datos o bloqueos indefinidos.

Para efectos de la simulación, el sistema contará con un catálogo de productos previamente definido por el estudiante. Desde la interfaz no será necesario crear nuevos productos, sino únicamente seleccionar uno de los productos existentes e indicar la cantidad de unidades recibidas.

Una vez registrada la llegada de mercadería, el resto del proceso deberá realizarse automáticamente. Inventario actualizará las existencias, asignará las ubicaciones correspondientes dentro del almacén y volverá a evaluar los pedidos que se encuentren esperando disponibilidad de ese producto.

---

## ALMACENAMIENTO

REDXela necesita llevar un control preciso de en qué ubicaciones físicas del almacén se encuentra guardada cada mercadería, ya que un mismo lote de productos puede ocupar varias ubicaciones si su volumen lo requiere, y esas ubicaciones no siempre son contiguas entre sí.

Un lote puede quedar repartido en distintos pasillos y niveles del almacén, según el espacio disponible en el momento de recibirlo.

---

## CONFLICTO DE RECURSOS

Bajo ciertas condiciones de alta demanda, es posible que dos operaciones del centro necesiten los mismos dos recursos compartidos, pero en orden inverso una respecto de la otra. En un sistema mal diseñado, esta situación deja a ambas operaciones esperando indefinidamente, sin que ninguna pueda completarse.

El sistema debe ser capaz de llegar deliberadamente a una situación de este tipo y presentar claramente en la interfaz:

- Qué recursos están involucrados.
- Qué operaciones están bloqueadas entre sí.
- Por qué se produjo el bloqueo.

A partir de ahí, el sistema debe ofrecer una forma de resolver la situación seleccionando manualmente cuál de las dos operaciones en conflicto continuará con los recursos disputados, mientras la otra regresa a la lista de espera para ser atendida más adelante.

---

## INTERFAZ DEL SISTEMA

El proyecto debe incluir una interfaz gráfica, de escritorio o web, que permita observar en tiempo real el funcionamiento interno del centro de distribución.

Como mínimo, un usuario del sistema debería poder:

- Ver el estado actual de cada recurso compartido: disponible, en uso y por quién.
- Ver los pedidos que se están procesando actualmente, su progreso y su tiempo restante estimado.
- Ver la lista de espera, organizada de forma que se entienda el criterio de orden aplicado.
- Consultar estadísticas generales del sistema:
  - Pedidos completados.
  - Tiempos promedio de espera.
  - Tiempos promedio de procesamiento.
  - Nivel de ocupación de los recursos.
- Registrar manualmente la llegada de nuevos pedidos o de nueva mercadería, para poder observar el efecto inmediato en el sistema.
- Visualizar y resolver la situación de conflicto de recursos descrita en la sección anterior.

El desarrollador puede incorporar cualquier otro elemento de interfaz que considere apropiado para comunicar con claridad el comportamiento del sistema.

---

## CONSIDERACIONES TÉCNICAS

Para el desarrollo del proyecto se solicita tomar en cuenta los siguientes elementos:

- El backend debe desarrollarse en **Java**, utilizando hilos y mecanismos reales de sincronización.
- El código debe estar comentado.
- El sistema debe manejar las excepciones.
- Para la calificación se evaluará no solo el correcto funcionamiento de la aplicación, sino que el estudiante demuestre, mediante su manual técnico y su capacidad de explicar el proyecto, que comprende los fundamentos teóricos detrás de cada decisión de diseño tomada.

---

## ENTREGABLES

Para la entrega se solicitan los siguientes elementos:

- Código fuente.
- Manual de Usuario.
- Manual técnico que incluya, además de la infraestructura, una explicación detallada de las decisiones tomadas durante el desarrollo del proyecto.

---

**Fecha de entrega: Martes 27 de octubre de 2026.**
