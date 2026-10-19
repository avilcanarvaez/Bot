# DragonShot Simulator

Aplicacion de escritorio para Windows que captura el area cliente de un juego,
detecta la inclinacion del canon, sigue el desplazamiento del escenario y dibuja
una trayectoria balistica sobre el juego en tiempo real.

Esta version mantiene una invariante estricta:

> **AIRE = 0** en todos los calculos. No hay arrastre, viento ni perdida de
> velocidad por aire. La interfaz muestra `AIRE` como campo fijo en `0`.

El proyecto es nuevo: no se encontro un proyecto previo ni una implementacion de
`SIMULAR`/`AIRE`/overlay en el repositorio analizado.

---

## 1. Requisitos

- Windows 10 2004 o superior (para `SetWindowDisplayAffinity` con
  `WDA_EXCLUDEFROMCAPTURE`). En Windows 11 esta verificado.
- JDK 21.
- Los JAR incluidos en `lib/`:
  - `jna-5.14.0.jar`
  - `jna-platform-5.14.0.jar`
  - `junit-platform-console-standalone-1.11.4.jar` (solo pruebas)
- Juego en **modo ventana** o **ventana sin bordes**. La captura usa
  `java.awt.Robot`, por lo que el juego debe estar visible y no tapado.

Rutas usadas en este equipo:

```text
JDK: C:\Users\avilcnar\Downloads\OpenJDK21U-jdk_x64_windows_hotspot_21.0.11_10\jdk-21.0.11+10
```

Si `JAVA_HOME` no esta definido, los scripts buscan automaticamente esa ruta y
algunas rutas habituales de JDK 21.

---

## 2. Compilar, probar y ejecutar

Desde la carpeta del proyecto:

```bat
build.cmd
test.cmd
run.cmd
```

Equivalente con PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\test.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
```

Si la politica de ejecucion de PowerShell bloquea los `.ps1`, usa los `.cmd`
(ya aplican `-ExecutionPolicy Bypass`) o pasa la ruta del JDK:

```bat
run.cmd -Jdk "C:\ruta\al\jdk-21"
```

Salida de compilacion:

```text
out\classes
out\test-classes
```

Ejecucion manual equivalente:

```powershell
java -cp "out\classes;lib\*" com.dragonshot.sim.App
```

Diagnosticos opcionales:

```bat
diagnose.cmd
overlay-smoke.cmd
```

- `diagnose.cmd`: comprueba DPI/coordenadas, enumeracion de ventanas, area
  cliente, exclusion de captura y `WS_EX_TRANSPARENT`.
- `overlay-smoke.cmd`: crea una ventana de fondo, muestra el overlay, comprueba
  que se puede hacer clic y que el punto marcado se convierte correctamente a
  coordenadas de escena.

---

## 3. Uso

1. Abre el juego en ventana o ventana sin bordes y dejalo visible.
2. Ejecuta `run.cmd`.
3. En **Ventana del juego**, pulsa **Actualizar** y selecciona la ventana.
4. Pulsa **Iniciar captura**. El overlay aparece sobre el area cliente.
5. Pulsa **Marcar ancla** y arrastra un rectangulo sobre un elemento **fijo del
   mundo** (suelo, roca, decorado). No uses HUD, botones, efectos ni el carrito.
   El ancla mide el desplazamiento de la camara.
6. Pulsa **Marcar origen** y haz clic exactamente sobre el eje de giro del canon
   (pivote del barril). El detector busca bordes en un radio alrededor de ese
   punto.
7. Pulsa **Marcar destino** y haz clic en el objetivo. Es opcional para dibujar
   la trayectoria, pero necesario para calcular la potencia sugerida.
8. Apunta el canon en el juego. El angulo se actualiza automaticamente; si no
   hay bordes suficientes, la interfaz muestra `—` y el motivo, nunca un valor
   inventado.
9. Ajusta **Potencia**, **Gravedad** y **Escala potencia**.
10. Pulsa **SIMULAR**. La trayectoria se dibuja con `AIRE = 0` y se marca como
    desactualizada si cambia el angulo, la potencia o la grafica de referencia.

### Calibrar el angulo

El detector devuelve el angulo **visual de pantalla** (0 = derecha, 90 = arriba,
180 = izquierda, 270 = abajo). Si el numero que muestra el juego no coincide:

1. Con el canon estable, pulsa **Calibrar angulo con valor conocido**.
2. Escribe el angulo que muestra el juego.
3. Marca si el angulo del juego aumenta en sentido **horario** o no.
4. El programa calcula y guarda un offset. La simulacion sigue usando el angulo
   visual real; el offset solo afecta a la etiqueta/calibracion del juego.

### Calibrar la potencia

La relacion entre potencia y velocidad (`v0 = potencia * escala`) es especifica
del juego y debe calibrarse:

1. Ajusta una potencia conocida, por ejemplo 50.
2. Dispara en el juego con el angulo actual.
3. Pulsa **Calibrar potencia con impacto** y haz clic en el punto real de
   impacto.
4. El programa despeja la velocidad necesaria y guarda la nueva escala.

La gravedad se mantiene como parametro fijo. Un solo disparo determina la
relacion `gravedad / escala²`; no se pueden identificar por separado gravedad y
escala con un unico impacto. Si cambias la gravedad, vuelve a calibrar.

`Correccion trayectoria (deg)` permite un ajuste fino si el angulo visual del
barril y la direccion real del proyectil difieren (por ejemplo, por la boca del
canon o por la convencion del juego).

---

## 4. Arquitectura

```text
Captura (Robot)
    |
    v
Frame gris + Rect del area cliente
    |
    +--> AnchorTracker (template matching multiescala)
    |        |
    |        v
    |    desplazamiento / zoom de camara
    |
    +--> AngleDetector (Sobel + Hough radial)
             |
             v
       AngleTracker (mediana circular + histeresis)
             |
             v
       SessionState  <---- UI (Swing)
             |
             v
       GamePipeline.simulate()
             |
             v
       Ballistics (AIRE = 0)
             |
             v
       OverlayWindow (JFrame transparente, siempre encima)
```

### Espacios de coordenadas

- **ESCENA**: `[0..1]` respecto del area cliente. Es el espacio persistente.
  Si la ventana se mueve o cambia de tamano, el punto conserva su posicion
  relativa.
- **LOCAL**: pixeles dentro del area cliente (`0,0` = esquina superior
  izquierda).
- **PANTALLA**: coordenadas AWT absolutas. Las usan `Robot`, `JFrame.setBounds`
  y los clics.

`CoordTransform` convierte entre los tres. `WindowTracker` obtiene el area
cliente con `GetClientRect` + `ClientToScreen` y aplica una unica escala DPI
calculada con `DpiScale.detect()`.

### Seguimiento del escenario

`AnchorTracker` guarda un parche de imagen del ancla y lo busca en el siguiente
frame con SAD (diferencia absoluta) y busqueda multiescala. Devuelve:

- `dx`, `dy` en pixeles,
- `scaleRatio`,
- `score` (confianza),
- `lostFrames` si no la encuentra.

`SessionState.applyAnchorUpdate` transforma **origen, destino, rectangulo del
ancla y trayectoria** alrededor del centro anterior del ancla:

```text
p' = centro + (p - centro) * escala + delta
```

Por eso el error relativo no se acumula y los puntos no se desincronizan al
mover la ventana o al hacer zoom de camara.

Si el ancla no se encuentra, `syncValid = false`. Tras dos fallos consecutivos
la deteccion de angulo tambien se declara no disponible para no usar un origen
viejo. El overlay muestra el motivo.

### Deteccion del angulo

`AngleDetector`:

1. Calcula bordes Sobel en una region cuadrada alrededor del origen.
2. Aplica una transformada de Hough limitada a lineas que pasan cerca del
   pivote (`rhoTolerance = 25 px`).
3. Puntua cada orientacion por el **maximo** de su histograma de `rho`, no por
   la suma de todos los `rho`. Esto evita que una textura difusa produzca un
   plateau artificial.
4. Elige el sentido (ida/vuelta) por el soporte de bordes a radios grandes.
5. Devuelve confianza, numero de bordes y motivo si no es fiable.

`AngleTracker` aplica una mediana circular de las ultimas muestras y tolera un
frame malo; tras dos fallos consecutivos limpia el estado y publica
`no disponible`. No reutiliza indefinidamente un angulo viejo.

### Balistica

Convencion de pantalla (Y crece hacia abajo):

```text
0°   = derecha
90°  = arriba
180° = izquierda
270° = abajo

vx = v0 * cos(theta)
vy = -v0 * sin(theta)
x(t) = x0 + vx * t
y(t) = y0 + vy * t + 0.5 * g * t²
```

`AIRE = 0` significa que no se suma ninguna aceleracion de arrastre. El unico
modelo usado por la aplicacion es `DragModel.NO_AIR`; `AIR_DENSITY` es una
constante `0.0`. La interfaz `DragModel` queda preparada para una futura version
con aire, pero **no se usa** en esta.

La potencia sugerida se obtiene despejando:

```text
v0² = g * dx² / (2 * cos²(theta) * (tan(theta) * dx + dy))
potencia = v0 / escalaPotencia
```

Si no existe una solucion finita (por ejemplo, tiro horizontal al mismo nivel,
objetivo detras del cañon o angulo vertical), se devuelve `NaN` y la interfaz lo
indica; no se inventa una potencia.

---

## 5. Pruebas y resultados reales

Ejecutado en este equipo con JDK 21.0.11, Windows 11, pantalla AWT 1920x1200,
escala DPI 1.0.

### JUnit 5

Comando: `test.cmd`

Resultado real:

```text
39 tests found
39 tests successful
0 tests failed
```

Cobertura:

- `BallisticsTest`: direccion en cuadrantes, angulo 0/90/180/270, `AIRE = 0`,
  velocidad requerida, ida/vuelta potencia-escala, decimacion, errores de
  parametros.
- `CoordTransformTest`: escena/local/pantalla, movimiento y resize de ventana,
  deltas normalizados, rectangulos del ancla.
- `TemplateMatcherTest`: parche exacto, desplazamiento del escenario, patch
  multiescala, rechazo de plantilla plana, delta del `AnchorTracker`.
- `AngleDetectorTest`: lineas sinteticas en 0, 30, 90, 135, 200 y 300 grados,
  rechazo de imagen vacia/pivote fuera, no devolver la direccion opuesta,
  suavizado e indisponibilidad.
- `SessionStateTest`: pan/zoom del mundo, angulo no disponible sin reutilizar el
  valor actual, trayectoria obsoleta al cambiar angulo/potencia/destino, mapeo
  horario/antihorario.
- `GamePipelineIntegrationTest`: captura sintetica -> ancla -> desplazamiento de
  camara de 7 px -> angulo actualizado -> `SIMULAR` con trayectoria hacia
  derecha/arriba y potencia sugerida.

### Rendimiento sintetico

Comando:

```powershell
java -cp "out\classes;out\test-classes;lib\jna-5.14.0.jar;lib\jna-platform-5.14.0.jar" com.dragonshot.sim.tools.PerfSmoke
```

Resultado real (frame 1280x720, ancla 40x40, radio de busqueda 70, 150
iteraciones):

```text
GamePipeline.onCapture: media 3.27 ms, p95 5.25 ms, max 7.06 ms
AngleDetector.detect: 1.96 ms/frame
Ballistics.simulate: 0.024 ms/llamada
```

El coste de `Robot.createScreenCapture` no esta incluido en esa cifra; depende
del tamano de ventana y del escritorio.

### Diagnostico de captura/exclusion

Comando: `diagnose.cmd`

Resultado real:

```text
AWT screen: 1920x1200
Win32 SM_CXSCREEN: 1920
DPI scale aplicada: 1.0
Area cliente Win32->AWT: Rect[x=120, y=120, width=300, height=200]
Pixeles verdes antes de excluir: 60000 / 60000
SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE) = true
Pixeles verdes despues de excluir: 0 / 60000
setClickThrough = true, WS_EX_TRANSPARENT = true
```

Esto verifica que la ventana de prueba se excluye realmente de
`Robot.createScreenCapture` en este Windows 11.

### Overlay y marcado

Comando: `overlay-smoke.cmd`

Resultado real:

```text
Overlay FindWindow: native@...
Overlay excluido de captura: true
Clic recibido: true
Punto de escena: (0.17, 0.25) (esperado ~0.167, 0.250)
Click-through restaurado tras el clic: true
OK: overlay y marcado funcionan.
```

---

## 6. Escenarios de aceptacion A-F

| Escenario | Que se valida | Evidencia |
|---|---|---|
| **A. Desplazamiento del escenario** | Al mover la camara, origen, destino, ancla y trayectoria se desplazan juntos; el marco de ventana no introduce offsets fijos. | `CoordTransformTest`, `SessionStateTest.cameraPanMovesAllWorldAnchoredElements`, `GamePipelineIntegrationTest.cameraShift...` |
| **B. Cambios de angulo** | El angulo sigue la inclinacion real en varios cuadrantes y no devuelve la direccion opuesta. | `AngleDetectorTest.detectsBarrelInMultipleDirections` (0, 30, 90, 135, 200, 300°), `doesNotReturnOppositeDirection` |
| **C. Direccion del disparo** | La trayectoria sale del origen en la direccion del angulo, no siempre hacia arriba. | `BallisticsTest.quadrantsHaveCorrectSigns`, `angleZeroGoesRightWithoutVerticalDirection`, `angle90GoesUp`, `angle180GoesLeft`, `angle270GoesDown`, integracion |
| **D. Integracion de SIMULAR** | `SIMULAR` usa el ultimo origen y angulo, dibuja la trayectoria y calcula potencia sugerida. | `GamePipelineIntegrationTest.cameraShiftKeepsPointsAlignedAndSimulationUsesLatestAngle` |
| **E. Condicion AIRE = 0** | No hay arrastre; el campo es constante y las trayectorias con/sin `NO_AIR` son identicas. | `BallisticsTest.trajectoryStartsAtOriginAndUsesAirZero`, `noAirProducesIdenticalTrajectories`, `AIR_DENSITY = 0` |
| **F. Estabilidad y rendimiento** | Sin excepciones en captura/overlay; coste por frame bajo; exclusion de captura y click-through verificados. | `test.cmd` (39/39), `PerfSmoke`, `diagnose.cmd`, `overlay-smoke.cmd` |

Importante: A-F estan validados con frames **sinteticos** y con la infraestructura
real de ventana/captura/overlay. No se dispone de frames reales del juego en este
entorno, por lo que la precision visual final (ancla, bordes del canon, escala de
potencia) requiere la calibracion descrita y una sesion real.

---

## 7. Limitaciones conocidas

1. **Sin frames reales del juego.** Los detectores estan probados con imagenes
   sinteticas y la infraestructura Windows esta verificada. La forma exacta del
   canon, skins, efectos y texturas del juego puede requerir ajustar radios,
   confianza y ancla.
2. **El ancla debe ser unica y visible.** Si se tapa, cambia de forma, rota con
   la camara o el zoom supera aproximadamente el rango de escalas (~0.88-1.12),
   el seguimiento se pierde. La interfaz lo indica; vuelve a marcar el ancla.
3. **Desplazamiento grande entre frames.** Si la camara se mueve mas que el radio
   de busqueda (70 px por defecto), se pierde el ancla. Se puede subir hasta 200
   px en opciones avanzadas o reducir el intervalo de captura.
4. **Resize de ventana.** Al cambiar el tamano se recaptura la plantilla del
   ancla en la nueva escala. Si durante el resize la camara tambien se movio, la
   recaptura puede fallar y hay que volver a marcar el ancla.
5. **DPI/multimonitor.** Se usa una unica escala basada en la pantalla principal.
   En configuraciones multimonitor con escalas DPI distintas puede haber deriva;
   usa el monitor principal o una configuracion DPI uniforme.
6. **Exclusion de captura.** Requiere Windows 10 2004+. Si
   `SetWindowDisplayAffinity` falla, el overlay puede aparecer en los frames y
   contaminar el ancla/detector. El programa avisa en el registro. En ese caso,
   manten el panel de control fuera del area del juego y considera usar otra
   version de Windows.
7. **Pantalla completa exclusiva.** `Robot` captura el escritorio; el juego debe
   estar en ventana/ventana sin bordes y visible. Si otra ventana lo tapa, se
   captura lo que se ve, no el contenido oculto.
8. **Angulo visual, no angulo interno del juego.** La deteccion es sobre la
   imagen. El offset/sentido se calibra con un valor conocido; si el juego usa
   otra convencion o el canon tiene un dibujo enganoso, el offset puede requerir
   ajuste.
9. **Fisica ideal.** `AIRE = 0`; no hay arrastre, viento ni giro. Si el juego real
   usa resistencia del aire, las trayectorias no coincidiran exactamente. La
   interfaz `DragModel` esta preparada para ampliarlo, pero no se usa en esta
   version.
10. **Gravedad y escala de potencia acopladas.** Un solo impacto calibra una
    combinacion de ambas. No se pueden determinar por separado con un disparo.
    Cambiar gravedad exige recalibrar.
11. **Potencia sugerida no siempre existe.** Tiros horizontales al mismo nivel,
    objetivos detras del canon o angulos verticales pueden no tener solucion
    finita; se informa con `NaN`/mensaje, no se inventa.
12. **Interaccion manual.** La UI y el overlay requieren escritorio; no hay modo
    headless para marcado ni para ver la trayectoria.

---

## 8. Solucion de problemas

**No aparece la ventana del juego en la lista**

- Pulsa **Actualizar** con el juego visible.
- Ejecuta el simulador con el mismo nivel de integridad que el juego (evita
  mezclar una consola elevada con un juego no elevado, o viceversa).
- Comprueba que la ventana no esta minimizada.

**El overlay no recibe clics al marcar**

- Verifica que `Iniciar captura` esta activo.
- Pulsa **Detener** y **Iniciar captura** de nuevo.
- Comprueba `diagnose.cmd`: debe mostrar `WS_EX_TRANSPARENT = true` cuando no
  hay interaccion.
- En Windows anterior a 10 2004 la exclusion de captura puede fallar; revisa el
  registro.

**El ancla se pierde**

- Elige una zona con textura y contraste, no una pared lisa ni un HUD.
- Aumenta **Busqueda ancla (px)** (maximo 200) y pulsa **Aplicar opciones**.
- Reduce el intervalo de captura (por ejemplo 50-70 ms).
- Evita que el carrito/efectos tapen el ancla.

**El angulo aparece como `—`**

- El origen debe estar exactamente en el eje de giro del canon.
- Ajusta **Radio minimo/maximo canon** y **Confianza minima**.
- Evita humo, destellos o efectos que oculten el barril.
- El mensaje de la interfaz indica si el problema es falta de bordes, pivote
  fuera del area o sincronizacion perdida.

**La trayectoria no pasa por el destino**

- Calibra primero el angulo con un valor conocido.
- Calibra la potencia con un impacto real.
- Ajusta **Correccion trayectoria** si el angulo visual y el disparo real
  difieren.
- Revisa que `AIRE = 0` sea coherente con el juego; si el juego aplica aire, no
  se puede compensar con esta version.

**Error de compilacion: no se encuentra javac**

- Define `JAVA_HOME` o pasa `-Jdk "C:\ruta\jdk-21"`.
- Usa los `.cmd` para evitar el bloqueo de politicas de PowerShell.

---

## 9. Archivos principales

```text
src/main/java/com/dragonshot/sim/
  App.java                         punto de entrada Swing
  capture/
    CaptureService.java            captura Robot periodica y captura puntual
    Frame.java                     frame en escala de grises
    CaptureEvent.java              frame + rectangulo
    WindowTracker.java             enumeracion, area cliente, click-through, exclusion
    WinUserEx.java                 bindings JNA adicionales
    WindowInfo.java                HWND/titulo/PID
    Rect.java                      rectangulo en pixeles AWT
    DpiScale.java                  escala Win32 -> AWT
  geometry/
    Vec2.java
    SceneRect.java
    CoordTransform.java            escena/local/pantalla
  physics/
    Ballistics.java                fisica ideal AIRE = 0
    Trajectory.java
    DragModel.java                 extension futura; NO_AIR en esta version
  vision/
    TemplateMatcher.java           SAD multiescala
    AnchorTracker.java             seguimiento del escenario
    EdgeMap.java                   Sobel
    AngleDetector.java             Hough radial del canon
    AngleTracker.java              mediana circular + histeresis
    AngleResult.java
  state/
    SessionState.java              estado compartido e inmutable por snapshot
  pipeline/
    GamePipeline.java              captura -> sync -> angulo -> simulacion
    SimulationException.java
    SimulationResult.java
  ui/
    MainFrame.java                 panel de control
    OverlayWindow.java             overlay transparente y marcado
  config/
    AppConfig.java                 calibracion persistente
  tools/
    Diagnostics.java               diagnostico de captura/DPI
    OverlaySmoke.java              prueba manual del overlay

src/test/java/com/dragonshot/sim/
  physics/BallisticsTest.java
  geometry/CoordTransformTest.java
  vision/TemplateMatcherTest.java
  vision/AngleDetectorTest.java
  state/SessionStateTest.java
  pipeline/GamePipelineIntegrationTest.java
  testutil/TestImages.java
  tools/PerfSmoke.java             benchmark sintetico
```

Configuracion persistente: `dragonshot.properties` en el directorio desde el que
se ejecuta la aplicacion. Se crea al guardar calibraciones o al cerrar.

---

## 10. Decisiones tecnicas y extension

- Se eligio Java 21 + Swing/AWT + JNA porque el entorno local tiene JDK 21 y no
  dispone de Python/Node/.NET en PATH.
- `java.awt.Robot` evita dependencias de captura nativas y funciona en ventana.
- El overlay es un `JFrame` no decorado, transparente, siempre encima, con
  `WS_EX_TRANSPARENT` para paso de clics y `SetWindowDisplayAffinity` para no
  capturarse a si mismo.
- Los puntos se guardan normalizados para no depender del tamano de ventana.
- El ancla se sigue con template matching; no se usan offsets fijos.
- El angulo se mide con bordes reales del frame, con confianza y estado
  `no disponible`; no se sustituye por un valor fijo.
- `AIRE = 0` es una constante y el unico modelo activo. Para una version futura
  con aire, se puede implementar otro `DragModel` y pasarlo a
  `Ballistics.simulate`, manteniendo la interfaz y las pruebas actuales.
