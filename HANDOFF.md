# Handoff — DragonShot / Extensión DragonBound

Documento para **continuar el trabajo en otra computadora**. Resume qué hay, cómo
ejecutarlo, qué está pendiente y cómo sincronizar.

---

## 1. Estado actual (resumen)

| Área | Estado |
|---|---|
| App Java de escritorio (captura + overlay + simulación) | **Implementada y probada** (39 tests OK). |
| Extensión de navegador (MV3) | **Implementada** en `extension/`; pruebas 27/27 y 54/54; calibración por móvil, cobertura de los 21 móviles y origen por móvil (v0.1.6), validación live pendiente. |
| Verificación contra el sitio real `dragonbound.net` | **PENDIENTE / bloqueante** (§16 del diseño). |
| Sincronización entre equipos | OneDrive activo + **GitHub** [`avilcanarvaez/Bot`](https://github.com/avilcanarvaez/Bot) (rama `main`, sincronizado). |

Invariante del proyecto: **`AIRE = 0`** en todos los cálculos.

---

## 2. Requisitos en la otra PC

- **Windows 10/11** para la app Java (usa JNA / Win32).
- **JDK 21** (con `javac`).
- Opcional: **Git** o GitHub Desktop para versionado.
- Para la extensión (fase futura): **Chrome/Edge** (MV3).

### JDK

Los scripts ahora buscan el JDK automáticamente (variable `JAVA_HOME`, `Program
Files`, `Adoptium`, `Microsoft`, `Corretto`, `Zulu`, `%USERPROFILE%\.jdks`, etc.).
Si no lo encuentran, define:

```bat
setx JAVA_HOME "C:\ruta\a\jdk-21"
```

y abre una consola nueva. También puedes pasar `-Jdk <ruta>` a los `.ps1`.

Comprobar:

```bat
java -version
javac -version
```

---

## 3. Cómo ejecutar

Desde la carpeta del proyecto:

```bat
build.cmd
test.cmd
run.cmd
```

Diagnóstico de captura/DPI (solo Windows):

```bat
diagnose.cmd
overlay-smoke.cmd
```

Si PowerShell bloquea los `.ps1`, usa los `.cmd` (ya aplican
`-ExecutionPolicy Bypass`) o:

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

Resultado esperado de `test.cmd`: **39 tests OK**.

---

## 4. Mapa de archivos

```
docs/
  DISENO_EXTENSION_WEB.md      <- diseño completo de la extensión (§16 = verificación real)
  mockup-extension.html        <- mockup visual de la extensión
src/main/java/com/dragonshot/sim/
  capture/    captura de ventana (Robot/JNA), DPI, click-through
  geometry/   coordenadas y transformaciones
  physics/    balística, DragModel.NO_AIR (AIRE=0)
  vision/     template matching, detección de ángulo (Hough)
  pipeline/   integración captura -> estado -> simulación
  state/      estado de sesión y staleness
  ui/         overlay y panel de control
  tools/      Diagnostics, OverlaySmoke
src/test/java/  pruebas JUnit + PerfSmoke + integración
lib/            jna, jna-platform, junit-console (versionados)
scripts/find-jdk.ps1   detección portable de JDK
dragonshot.properties  calibración actual
out/            build (ignorado por git)
```

---

## 5. Qué está pendiente (prioridad)

1. **(BLOQUEANTE) Verificación real** — `docs/DISENO_EXTENSION_WEB.md` §16.
   - `https://dragonbound.net/` está bloqueado desde este equipo (403 / Cloudflare).
   - Hace falta ejecutar el checklist §16.3 desde una red con acceso, o aportar un
     **HAR con frames de WebSocket** + captura para analizarlo offline.
   - Confirmar: motor (Pixi?), URL del WS, opcodes, constantes (`398`, `2.34`, `485`),
     lectura de `ang`, viewport/CSP.
2. Validar la extensión en partida real (destinos izquierda/derecha, `look=0/1`, `body ≠ 0`).
   v0.1.3 corrige la **inversión vertical** del cañón (`look=0 → real = −raw`,
   `look=1 → real = raw`); ver §13 de la sesión y §16.15 del diseño. **Confirmado
   en vivo con 8 disparos**: el mensaje `play` publica `truth`/`raw` del mismo
   instante y el residuo fue 0° en ambos lados (look=0: 231→129, 235→125,
   230→130, 206→154; look=1: 50→50, 63→63).
3. **(Detallado, no implementado) Calibración de potencia por tipo de bala** —
   ver §16.16 del diseño. En la sesión live se disparó con la **segunda bala**
   (`#btnShot2.Pressed`) mientras los fixtures son de la primera
   (`selectedShot=0`); el perfil quedó en `2.2919/386` frente a `2.3641/402`
   (~3–4 % en `v0`, decenas de píxeles de error). Causa: la calibración no
   separa por `selectedShot` (desde v0.1.4 los cubos son por móvil, no por
   bala); además la ruta sintética del hook fija `selectedShot: 0` y
   `pendingShots` no compara el tipo al emparejar con `play`. Propuesta: cubos
   por `(móvil, bala)` con fallback, `readSelectedShot()` desde
   `#btnShot1/#btnShot2/#btnShotSS` (`Pressed`), y mostrar `S1/S2/SS` en el
   panel. Criterio: 5 tiros por bala, `powerScale` estable < 1 % y sin
   regresiones 23/23 + 50/50.
4. **(Implementado v0.1.4, validación live pendiente) Calibración por móvil:
   Boomer.** La gravedad es por móvil (`a`: Armor 75, Boomer 62,5, Dragon2 120);
   antes se promediaba entre móviles y Boomer heredaba la de Armor (17 % de más).
   Ahora `AutoCalibration` guarda cubos `Map<móvil, {power, gravity}>`, normaliza
   la clave con `mobileKey()` y **predice** la gravedad de un móvil sin muestras
   con `unidad = s[5]/a` (Armor 402 → unidad 5,36 → Boomer 335; sin datos,
   `390/75·62,5 = 325`). La potencia hereda la mediana global como respaldo.
   Detalle en §16.17 del diseño. Falta: confirmar en vivo que Boomer cae en
   ~335 con 5 disparos. Criterio: `powerScale` estable < 1 %, sin regresiones.
   La v0.1.5 extiende la cobertura a los **21 móviles** de la tabla con pruebas
   de predicción y de potencia/trayectoria (Armor vs Boomer vs Dragon2); ver
   §16.18 del diseño.
5. **(Implementado v0.1.6, validación live pendiente) Origen del cañón por
   móvil.** `AutoCalibration` resuelve `(a, c)` desde `play.s[0]/s[1]` y lo
   guarda por móvil. En modo `auto` se aplica tras **3 muestras estables**
   (MAD ≤ 2); Armor conserva su valor ajustado (`TUNED_ORIGINS`) y solo se
   mide. Opciones: `calibrateOrigin: 'auto' | true | false`,
   `minOriginSamples`, `maxOriginMad`. El perfil publica `originSource`
   (`measured` / `tuned` / `default`) y `originMeasured` (`mad`, `stable`).
   Detalle en §16.19 del diseño. Falta: validar en vivo que el offset medido de
   Boomer y del resto coincide con el cañón real.
6. Afinar detección con frames reales del juego (si se usa el plan B de visión).

---

## 6. Cómo sincronizar entre equipos

### Opción A — OneDrive (ya activo, la más simple)

Esta carpeta ya está dentro de OneDrive for Business. En la otra PC:

1. Inicia sesión con **la misma cuenta**.
2. Deja que se sincronice `Documentos\Proyecto predeterminado`.
3. Abre la carpeta y ejecuta `test.cmd`.

Cuidados: evita editar/compilar en ambas PCs a la vez (conflictos), y pausa la
sincronización mientras compilas si notas archivos bloqueados.

### Opción B — Git + repositorio privado (recomendada)

Ventajas: historial, ramas, diffs, y no dependes del sync de archivos.

**Ya está subido:** repositorio local + remoto GitHub
[`avilcanarvaez/Bot`](https://github.com/avilcanarvaez/Bot), rama `main`.

Para traerlo a la otra PC:

```bat
git clone https://github.com/avilcanarvaez/Bot.git
cd Bot
test.cmd
```

Para seguir trabajando en esta PC:

```bat
git pull
git add .
git commit -m "descripcion del cambio"
git push
```

> La primera vez, **Git Credential Manager** pedirá iniciar sesión en GitHub
> (navegador o token).

> `.gitignore` ya excluye `out/` y archivos de IDE. Los `.jar` de `lib/` sí se
> versionan (5.8 MB) para que compile sin descargar nada.

### Opción C — Ambas (recomendada si hay OneDrive)

Mantén OneDrive como copia automática y Git como historial/remoto. Lo importante es
**no** trabajar simultáneamente en las dos PCs sobre los mismos archivos.

---

## 7. Convenciones y avisos

- **`AIRE = 0` es invariante.** No introducir arrastre/viento sin cambiar el diseño.
- No borrar controles existentes sin justificar.
- No declarar que "funciona en producción" sin completar §16.
- El repo git local **ya está inicializado** (rama `main`, commit `2453912`).
- `dragonshot.properties` contiene la calibración actual; si la otra PC tiene otro
  juego/resolución, vuelve a calibrar.

---

## 8. Próximo paso sugerido

1. En la otra PC: `test.cmd` (confirmar 39 tests).
2. Conseguir acceso a `dragonbound.net` (o un HAR) y ejecutar §16.3.
3. Con el perfil confirmado, empezar la **Fase 0–1** de la extensión.
