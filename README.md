# Caracol Interactivo

Proyecto Android de la Unidad 2 de TI3042. El celular de la educadora controla la maqueta por Bluetooth Classic. El de la directora consulta las lecturas y el historial por Wi-Fi en modo de solo lectura. Ambos usan la misma APK.

## Abrir y compilar

1. Abre esta carpeta en Android Studio con **File → Open**.
2. Usa el JDK incluido con Android Studio y el SDK **Android 17 (API 37.0)**. La app requiere Android 8.0 o superior y tiene SDK objetivo 36.
3. Acepta la sincronización de Gradle y selecciona **Run** o ejecuta **.\gradlew.bat :app:assembleDebug**. Gradle descarga la distribución y sus dependencias la primera vez.
4. Instala **app/build/outputs/apk/debug/app-debug.apk** en los dos teléfonos.

## Configurar los teléfonos

1. En el celular A selecciona **Educadora → Crear cuentas en este celular**. Crea usuarios y contraseñas diferentes para los dos roles.
2. Ingresa como educadora. Para el montaje real, vincula el HC-05 desde Ajustes y selecciónalo en **Conectar Bluetooth**. Acepta el permiso Dispositivos cercanos. Para un ensayo, pulsa **Iniciar simulación** y mueve el control de nivel.
3. Conecta ambos equipos a una red Wi-Fi con acceso entre dispositivos. En A pulsa **Compartir por Wi-Fi**. Confirma directamente con la educadora la IP y la huella que muestra A.
4. En el celular B selecciona **Directora** e ingresa sus credenciales y el texto completo **IP#huella**. Abre **Monitoreo** o **Ver historial**. El Activador está disponible en A.
5. Mantén A con la app visible durante la ejecución. Al salir de la app, A cierra ambos enlaces. Para continuar, vuelve a conectar Bluetooth y activa la opción de compartir en A. B deberá volver a autenticar su cuenta.

La aplicación identifica las lecturas de simulación. El porcentaje se deriva de un valor de 0 a 1023 y representa ruido simulado.



## Estructura

- **MainActivity.kt** y **ScreenUi.kt** implementan acceso, navegación y pantallas con controles Material nativos.
- **DataStore.kt** contiene SQLite, verificación de contraseñas con PBKDF2 y cifrado AES-GCM mediante Android Keystore. SQLite retiene las últimas 300 lecturas y la vista consulta hasta 30.
- **BluetoothIo.kt** implementa el enlace RFCOMM y los pulsos de actividad.
- **LocalNetwork.kt** ofrece autenticación y consulta por HTTPS local. Verifica la huella SHA-256 del certificado de A y usa tokens de sesión temporales. El servidor publica solo lectura.
- **firmware/** contiene el firmware de la maqueta y su estado seguro.

## Validación para la exposición

| Prueba | Resultado esperado |
| --- | --- |
| Login correcto, contraseña errónea y cinco fallos | Se respeta el rol y se aplica una espera de 60 s tras cinco fallos |
| Dos Android, misma red | B recibe lecturas y consulta el historial. Solo A envía órdenes |
| Huella incorrecta | B rechaza el enlace HTTPS antes de autenticarse |
| Perilla por debajo y por encima del umbral | El estado automático cambia y Mostrar se rechaza con nivel alto |
| Corte Bluetooth o reinicio de Arduino | Caracol escondido, LED y zumbador apagados. El control se recupera tras el rearme |
| Corte Wi-Fi en B | Los datos se marcan como desactualizados y A mantiene el control Bluetooth |

Captura únicamente cuentas de prueba y datos de la maqueta al documentar la exposición.
