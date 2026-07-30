# Calculador Uber Net ARS - App de Android

Esta es una aplicación nativa de Android desarrollada en Kotlin que ayuda a los conductores de Uber en Argentina a calcular, en tiempo real, las ganancias netas por kilómetro recorridas en cada viaje, restando el costo estimado del combustible (Nafta o GNC).

## Características principales
- **Cálculo automático inteligente:** Extrae el precio y las distancias de la pantalla de Uber. Suma automáticamente la distancia para ir a buscar al pasajero (pickup) y la distancia del viaje propiamente dicho (ej. 2 km al pasajero + 8 km de viaje = 10 km total) para calcular la rentabilidad real sobre los kilómetros totales que vas a recorrer.
- **Deducción de combustible:** Configura tu combustible activo (Nafta o GNC), ingresa el precio actual del litro e indica el rendimiento de tu auto (por defecto, 10 km/L).
- **Ventana flotante inteligente:** Muestra un cartel elegante sobre Uber con el cálculo neto por kilómetro, pintando de **verde** si supera tu umbral de alta rentabilidad, **amarillo** si es regular o **rojo** si no es rentable. Se cierra solo tras 15 segundos para no molestar.
- **Simulador integrado:** Permite simular viajes de prueba desde la aplicación para ver cómo funciona el cartel sin esperar un viaje real.

---

## Estructura del Proyecto
- [MainActivity.kt](app/src/main/java/com/personal/ubernetcalc/MainActivity.kt): Administra los ajustes guardados, los umbrales de rentabilidad y lanza las simulaciones de prueba.
- [UberAccessibilityService.kt](app/src/main/java/com/personal/ubernetcalc/UberAccessibilityService.kt): Escucha la pantalla de Uber Driver, limpia los caracteres (soporta separador de miles por punto y decimales por coma típicos de ARS), realiza la lógica matemática y dibuja la burbuja flotante.
- [.github/workflows/android.yml](.github/workflows/android.yml): Script de compilación automática en la nube (GitHub Actions).

---

## Cómo Compilar e Instalar la App

Dado que es una app personal, puedes instalarla en tu Samsung Galaxy A24 de dos formas sencillas:

### Opción 1: Compilación Automática en la Nube (Recomendado si usas GitHub)
Si tienes una cuenta de GitHub, no necesitas instalar herramientas de desarrollo en tu computadora:
1. Crea un repositorio privado o público en tu cuenta de GitHub.
2. Sube (haz push o sube los archivos manualmente) todo este directorio `uber-net-calculator` a tu repositorio.
3. Ve a la pestaña **Actions** en tu repositorio de GitHub. Verás que se iniciará automáticamente un flujo de trabajo llamado `Android CI`.
4. Cuando termine (tarda 2-3 minutos), haz clic en la última ejecución de la tarea, desplázate hasta abajo a la sección **Artifacts** y descarga el archivo `app-debug-apk` (es un archivo `.zip` que contiene tu APK listo).
5. Descomprímelo, transfiere el archivo `app-debug.apk` a tu teléfono Samsung e instálalo (deberás permitir la instalación de apps de fuentes desconocidas).

### Opción 2: Compilación Local con Android Studio
Si tienes o deseas instalar Android Studio en tu computadora:
1. Descarga e instala [Android Studio](https://developer.android.com/studio).
2. Abre Android Studio y elige **Open** (Abrir), luego selecciona esta carpeta `uber-net-calculator`.
3. Deja que Android Studio configure el proyecto (descargará Gradle automáticamente).
4. Conecta tu Samsung Galaxy A24 a la computadora mediante cable USB.
   - En tu teléfono, activa las **Opciones de Desarrollador** (ve a Ajustes -> Acerca del teléfono -> Información de software -> presiona 7 veces seguidas en "Número de compilación").
   - Entra al nuevo menú Ajustes -> Opciones de desarrollador y activa la **Depuración por USB**.
5. En Android Studio, selecciona tu teléfono Samsung en la lista de dispositivos de la barra superior y presiona el botón verde de reproducir (**Run** / `Shift + F10`). La aplicación se instalará e iniciará en tu teléfono automáticamente.

---

## Cómo Usar y Configurar la Aplicación

1. **Configura tus precios:**
   - Abre la aplicación **Uber Net Calc** en tu teléfono.
   - Ingresa el precio actual por litro de la Nafta y del GNC.
   - Selecciona cuál de los dos combustibles estás utilizando en ese momento.
   - Configura el rendimiento (por defecto `10.0` km por litro).
   - Configura tus umbrales (ejemplo: Alto = `250` ARS/km, Bajo = `120` ARS/km).
   - Presiona **Guardar Configuración**.
2. **Activa los Permisos del Sistema:**
   - Presiona el botón **Activar Servicio de Accesibilidad** en la app.
   - El sistema te enviará a los Ajustes de Accesibilidad de tu Samsung.
   - Busca **Calculador Uber Net** (o Apps Instaladas / Servicios Instalados -> Calculador Uber Net).
   - Enciéndelo y dale los permisos solicitados. (Es normal que Android advierta sobre lectura de pantalla, la app lo requiere para leer el precio y km de la pantalla de Uber).
   - *Nota:* Android podría pedirte el permiso de "Mostrar sobre otras aplicaciones" si es la primera vez. Concédelo.
3. **Prueba el Simulador:**
   - Vuelve a la app, ingresa un precio de prueba (ej. `4000`) y distancia (ej. `8.2`).
   - Toca **Simular Oferta Uber**.
   - Si todo está bien configurado, ¡verás aparecer inmediatamente una burbuja flotante semitransparente con los cálculos en tu pantalla! Toca la cruz `X` para cerrarla, o espera 15 segundos y desaparecerá sola.
4. **¡Listo para Conducir!**
   - Ahora puedes abrir tu app de **Uber Driver** normalmente.
   - Cada vez que entre una oferta en pantalla, el recuadro flotante te indicará al instante tu ganancia neta real por kilómetro.
