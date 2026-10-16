# EnderSMP Launcher

Launcher que mantiene el modpack **EnderSMP** (Minecraft 1.20.1 · Forge 47.4.12) siempre actualizado.
El jugador abre el launcher y el modpack se instala y se actualiza solo. Después, **Jugar**.

## Para jugadores

1. Descarga el launcher desde [Releases](../../releases/latest):
   - `EnderSMP-Launcher-Windows.zip`: descomprímelo y abre `EnderSMP Launcher.exe` (lleva Java incluido).
   - `EnderSMP-Launcher.jar`: si ya tienes Java 21 (Windows, Mac o Linux).
2. La primera vez te pregunta con qué juegas:
   - **Modrinth** (recomendado): se abre Modrinth App pidiendo confirmar el perfil nuevo **"EnderSMP"** con Forge 47.4.12. Acéptalo y el launcher lo detecta solo.
   - **Prism Launcher**: igual, Prism pide confirmar la importación.
   - **Launcher oficial**: al darle a *Jugar* instala Forge solo y crea el perfil "EnderSMP".
   Puedes cambiarlo cuando quieras en **Instalar en**, debajo del título.
3. Cada vez que abras el launcher descarga lo que haya cambiado en GitHub. Dale a **Jugar** y arranca Modrinth con el perfil.

Si en la instancia hay mods que no son del modpack, el launcher avisa antes de quitarlos (en ese caso no actualiza solo).

## Para el creador del modpack

| Quieres… | Haz esto |
|---|---|
| Añadir o actualizar un mod | Sube el `.jar` a `Modpack/mods/` (y borra el viejo) |
| Quitar un mod | Bórralo de `Modpack/mods/`: se les borra a todos al actualizar |
| Cambiar una config | Sube el archivo a `Modpack/config/` |
| Publicar una noticia | Edita `news.json` |
| Cambiar imágenes, colores o textos del launcher | Sube archivos a `launcher-assets/` (ver abajo) |

`manifest.json` se regenera solo (GitHub Action *Actualizar manifest*). No hace falta tocarlo.

> **El repositorio tiene que ser público.** El launcher descarga los archivos de `raw.githubusercontent.com`, que no funciona con repos privados.

**Archivos de más de 25 MB**: la web de GitHub no deja subirlos arrastrando. Usa [GitHub Desktop](https://desktop.github.com/) o `git push`. El límite por archivo es 100 MB.

### Cómo se actualiza cada carpeta

- `mods/`: queda **idéntica** a la del repositorio (lo que sobra se borra).
- `config/`, `defaultconfigs/`, `resourcepacks/`, `shaderpacks/`… : se añaden o reemplazan los archivos del repositorio, sin borrar nada.
  Si un jugador cambia una config, se respeta hasta que tú subas una versión nueva de ese archivo.

## Personalizar el aspecto (`launcher-assets/`)

Todo lo visual sale de esta carpeta. Cambia un archivo, súbelo a GitHub y **la próxima vez que alguien abra el launcher lo verá**, sin recompilar ni publicar una versión nueva.

| Archivo | Qué es | Recomendado |
|---|---|---|
| `fondo.png` | Fondo de toda la ventana (se recorta para llenarla) | 1600×900 o más. Si lo borras, se usa un degradado |
| `logo.png` | Imagen grande de la izquierda (gira mientras descarga) | PNG cuadrado con fondo transparente, 512×512 |
| `icono.png` | Icono de la ventana y del `.exe` | PNG cuadrado, 256×256 |
| `fuente.ttf` | Tipografía de títulos y botones (opcional) | Cualquier `.ttf` |
| `fuente-titulo.ttf` | Tipografía solo del título grande (ahora: Monocraft cursiva, estilo Minecraft, licencia OFL) | Cualquier `.ttf` |
| `tema.json` | Colores y textos | Ver abajo |

`tema.json` (si quitas una línea, se usa el valor por defecto; colores en `#RRGGBB`):

| Clave | Para qué |
|---|---|
| `titulo`, `subtitulo`, `tituloVentana`, `tituloNovedades` | Textos de la cabecera |
| `tamanoTitulo` | Tamaño del título (60 por defecto) |
| `colorTitulo`, `texto`, `textoSecundario` | Colores del texto |
| `botones`, `botonesHover`, `textoBotones` | Botones principales |
| `barraInicio` | Color inicial de la barra de progreso (termina en el de `botones`) |
| `fondoArriba`, `fondoAbajo` | Degradado de fondo (se ve si no hay `fondo.png`) |
| `oscurecerFondo` | Velo oscuro sobre `fondo.png` para que se lea el texto (0 = nada, 1 = negro) |
| `logoGira`, `halo`, `tamanoLogo` | El logo gira (`true`/`false`), brillo detrás, tamaño (0.2–1.0) |
| `dialogos` | Fondo de las ventanas de aviso |

El icono del `.exe` sí necesita una versión nueva del launcher (se compila con él): al subir `icono.png` se recompila solo, y aparece en la siguiente release que publiques.

## Publicar una versión nueva del launcher

1. Sube `LAUNCHER_VERSION` en `launcher-src/Launcher.java` (por ejemplo a `1.0.1`).
2. La Action *Compilar launcher* genera el `.exe` y el `.jar` y crea la release `v1.0.1` sola.
   Los launchers viejos avisarán de que hay una versión nueva.

## Cambiar la versión de Minecraft o Forge

Edita `MINECRAFT` y `FORGE` en `.github/scripts/generate_manifest.py`. Los perfiles nuevos usarán esa versión.

## Cambiar el nombre o el repositorio

Las constantes están al principio de `launcher-src/Launcher.java` (`PACK_NAME`, `REPO`, `RAM`, `AUTO_UPDATE`…).
