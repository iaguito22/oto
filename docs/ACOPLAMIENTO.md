# Ventanas acopladas de Oto

Trabajo aislado en `oto-acoplamiento`, rama `feat/prueba-acoplamiento`. Parte del estado de trabajo actual de `oto-modular`; no se han descartado ni mezclado sus cambios en el árbol original.

## Uso

La aplicación normal inicia biblioteca y reproductor unidos si todavía no hay una distribución guardada. Biblioteca y reproductor tienen accesos para abrir los módulos; biblioteca también comparte el menú de tres puntos del reproductor.

- Las ventanas nuevas se reparten a ambos lados de la jefa, según el ancho ocupado; las conocidas recuperan su disposición anterior.
- Prioridad: biblioteca, reproductor y, al mismo nivel, letra/cola. Entre iguales dirige quien recibe a la otra ventana.
- Arrastrar la cabecera de la jefa mueve el grupo. Arrastrar una secundaria dentro de la franja de cabeceras permite reordenarla: inserción en el mismo lado, intercambio al cruzar la jefa. La marca de borde anticipa el destino.
- Alejarla verticalmente de las cabeceras, o salir por un extremo, la separa. Se lleva las piezas exteriores de menor prioridad que dependen de ella. Las dependencias reflejan siempre el orden actual.
- Acercar los bordes exteriores de dos grupos muestra el imán; soltar confirma la unión. Comparten altura y cada módulo conserva su ancho.
- El redimensionado vertical afecta al grupo completo, también cuando el escritorio captura el gesto del borde. Cerrar solo quita esa ventana y compacta las demás. Pulsar una ventana eleva su grupo.
- La chincheta aparece exclusivamente con reproductor solo. Acoplarlo la desactiva y separarlo no la recupera automáticamente.

En Hyprland no hay controles ni doble clic de maximizar. En Windows y otros escritorios Linux se puede maximizar/restaurar desde la cabecera, incluyendo los cambios de maximización nativos. El conjunto usa el área útil del monitor. Biblioteca absorbe el ancho sobrante; sin ella, letra, cola o reproductor, en ese orden. Al añadir módulos se reduce primero el exceso de las secundarias hasta su ancho predeterminado y después biblioteca hasta su mínimo. Si no cabe, el módulo abre suelto y se informa con un aviso breve. Separarlo del conjunto maximizado recupera su tamaño normal bajo el cursor.

La distribución, los tamaños normales y los destinos de las ventanas cerradas se guardan en `docking_v1` dentro de las preferencias existentes de ventanas. Un archivo inválido vuelve a la disposición inicial. Al faltar un monitor se recuperan posiciones visibles y, si es necesario, se separan grupos que ya no caben.

## Arnés independiente

```sh
./tools/run-dock-harness.sh --offline
```

Utiliza los mismos marcos y controlador que la aplicación con contenido de prueba. No inicia servicios, reproducción, sincronización ni preferencias del usuario. `OTO_DOCK_TRACE=1` activa las trazas de gestos y geometría.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktopApp:test --offline
```

## Comprobaciones

25 pruebas de escritorio sin fallos (23 de acoplamiento y 2 existentes de audio). Pruebas automatizadas de geometría, prioridades, separación con dependientes, inserción/intercambio, cierre y reapertura, persistencia, datos inválidos, maximización, límites de ancho, reparto inicial, recuperación de monitores y áreas reservadas con escalas/rotación y coordenadas negativas.

Verificación visual en Hyprland con `agy-pantalla`: arrastre conjunto, separación, imán izquierdo, inserción, intercambio a través de la jefa, restauración de la distribución, altura compartida desde el borde nativo, aviso de espacio insuficiente, menús y chincheta (se desactiva al acoplar y no se recupera al separar). La maximización se comprobó en la primera prueba antes de retirarla de la interfaz de Hyprland; sus reglas siguen cubiertas por pruebas.

Pendiente: comprobación visual en Windows, otro escritorio Linux y monitores físicos con escalas distintas. En la aplicación real se han observado avisos repetidos del código existente de sincronización local cuando el otro dispositivo está desconectado; se mantiene ese problema separado del controlador de ventanas.
