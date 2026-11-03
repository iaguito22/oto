# Continuar el desarrollo de Oto

La rama canónica es `main`, que reúne Android, Windows y Linux. No utilizar una versión distinta por sistema operativo ni reemplazar cambios locales con un clon antiguo.

1. Leer `AGENTS.md`, comprobar `git status --short --branch` y conservar el trabajo local.
2. Consultar `docs/PUBLICACION.md` para paquetes, firmas y releases.
3. Ejecutar las pruebas de los módulos modificados. El escritorio requiere JDK 21; Android usa las versiones del catálogo Gradle.
4. Las credenciales, cookies, keystores y perfiles permanecen fuera del repositorio. La variante Android `phone` y el APK público usan identificadores y firmas diferentes: comprobarlos antes de distribuir o instalar.
5. No afirmar que un cambio está instalado o publicado solo porque compile. Verificar los paquetes y el arranque real del sistema correspondiente.

El historial de acoplamiento y las revisiones anteriores se conserva en Git y en `docs/`. Las preferencias de primer arranque son reproductor centrado, aleatorio activado y disposición al abrir módulos: letra / biblioteca / reproductor, con cola independiente.