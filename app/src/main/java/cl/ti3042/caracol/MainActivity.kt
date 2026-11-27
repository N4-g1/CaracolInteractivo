package cl.ti3042.caracol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class Screen { CONNECTION, MONITOR, ACTUATOR }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val writes = Executors.newSingleThreadExecutor()
    private val feed = LiveFeed()
    private val ui by lazy { ScreenUi(this) }
    private val store by lazy { DataStore(applicationContext) }
    private val preferences by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private var visible = false
    private var accountsKnown = false
    private var hasAccounts = false
    private var configuring = false
    private var busy = false
    private var desiredRole = Role.EDUCATOR
    private var role: Role? = null
    private var expiresAt = 0L
    private var screen = Screen.CONNECTION
    private var status = ""

    private var server: MonitorServer? = null
    private var shareEpoch = 0
    private var shareBusy = false
    private var shareStatus = ""
    private var remote: RemoteMonitor? = null
    private var remoteToken: String? = null
    private var remoteSnapshot = Snapshot(null, false, null)
    private var remoteObservedAt = 0L
    private var nextPoll = 0L
    private var pollFailures = 0
    private var polling = false
    private var frozen: Snapshot? = null

    private var bluetooth: BluetoothIo? = null
    private var bluetoothEpoch = 0
    private var bluetoothConnected = false
    private var bluetoothReady = false
    private var bluetoothStatus = "Desconectado"
    private var selectedDevice = ""
    private var selectedAction = Action.HIDE
    private var commandId = 0
    private var pending: Int? = null
    private var actionStatus = "Elige una acción y confirma el envío."

    private var simulation = false
    private var simulationValue = 348
    private var simulationHidden = false
    private var simulationSequence = 0L
    private var nextSample = 0L
    private var monitorValue: TextView? = null
    private var monitorDetail: TextView? = null
    private var monitorSource: TextView? = null
    private var monitorProgress: ProgressBar? = null
    private var connectionLabel: TextView? = null
    private var actionLabel: TextView? = null
    private var sendButton: Button? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            val now = SystemClock.elapsedRealtime()
            if (role != null && now >= expiresAt) {
                signOut("La sesión venció. Ingresa de nuevo.")
            } else if (role == Role.EDUCATOR && simulation && now >= nextSample) {
                sampleSimulation()
            } else if (role == Role.DIRECTOR && frozen == null && now >= nextPoll) {
                poll()
            }
            updateUi()
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        render()
        background({ store.hasAccounts() }) { result ->
            accountsKnown = true
            hasAccounts = result.getOrDefault(false)
            if (result.isFailure) status = "No se pudo abrir el almacenamiento local."
            render()
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        main.post(tick)
    }

    override fun onStop() {
        visible = false
        main.removeCallbacks(tick)
        if (role == Role.EDUCATOR) {
            stopSource()
            stopShare()
            render()
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopSource()
        stopShare()
        io.shutdown()
        writes.shutdown()
        super.onDestroy()
    }

    private fun render() {
        monitorValue = null
        monitorDetail = null
        monitorSource = null
        monitorProgress = null
        connectionLabel = null
        actionLabel = null
        sendButton = null

        val root = ui.column().apply { setBackgroundColor(ui.background) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, keyboard.bottom))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = ui.column(13).apply {
            setPadding(ui.dp(21), ui.dp(15), ui.dp(21), ui.dp(23))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        if (role == null) {
            header(body, getString(R.string.app_name), "Monitoreo de ruido simulado en el aula")
            when {
                !accountsKnown -> ui.item(body, ui.label("Preparando almacenamiento…"))
                configuring -> setupForm(body)
                else -> loginForm(body)
            }
        } else {
            val title = when (screen) {
                Screen.CONNECTION -> "Conexión"
                Screen.MONITOR -> "Monitoreo"
                Screen.ACTUATOR -> "Activador"
            }
            header(body, title, role!!.label + if (role == Role.DIRECTOR) " · Solo lectura" else "")
            when (screen) {
                Screen.CONNECTION -> connectionScreen(body)
                Screen.MONITOR -> monitorScreen(body)
                Screen.ACTUATOR -> actuatorScreen(body)
            }
            ui.item(body, ui.button("Cerrar sesión") { signOut("Sesión cerrada.") })
            if (role == Role.EDUCATOR) root.addView(navigation())
        }
        setContentView(root)
        root.requestApplyInsets()
        if (role != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateUi()
    }

    private fun header(body: LinearLayout, title: String, subtitle: String) {
        ui.item(body, ui.column(5).apply {
            ui.item(this, ui.label(title, 29f, true))
            ui.item(this, ui.label(subtitle, 15f, color = ui.softInk))
        })
    }

    private fun loginForm(body: LinearLayout) {
        val card = ui.card("Iniciar sesión")
        val roles = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Role.entries.forEach { option ->
            roles.addView(choice(option.label).apply {
                isChecked = desiredRole == option
                isEnabled = !busy
                setOnClickListener {
                    if (desiredRole != option) {
                        desiredRole = option
                        status = ""
                        render()
                    }
                }
            })
        }
        ui.item(card, roles)
        val user = ui.field("Usuario", TEXT, 32)
        val password = ui.field("Contraseña", PASSWORD)
        ui.item(card, user)
        ui.item(card, password)
        val reveal = Switch(this).apply {
            setText(R.string.reveal_password)
            minHeight = ui.dp(48)
            setOnCheckedChangeListener { _, shown ->
                password.transformationMethod = if (shown) null else PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        }
        ui.item(card, reveal)
        val endpoint = if (desiredRole == Role.DIRECTOR) {
            ui.field("IP#huella del celular A", TEXT, 100).apply {
                setText(preferences.getString("endpoint", ""))
                ui.item(card, this)
            }
        } else null
        val message = ui.label(status, color = ui.warning)
        if (status.isNotEmpty()) ui.item(card, message)
        val submit = ui.button(if (busy) "Validando…" else "Ingresar", true) {
            val username = user.text.toString().trim()
            val secret = password.text.toString()
            if (username.isBlank() || secret.isEmpty()) {
                toast("Completa el usuario y la contraseña.")
                return@button
            }
            when (desiredRole) {
                Role.EDUCATOR -> {
                    if (!hasAccounts) { toast("Crea las cuentas en el celular A."); return@button }
                    startEducator(username, secret)
                }
                Role.DIRECTOR -> startDirector(username, secret, endpoint?.text.toString())
            }
        }
        ui.enable(submit, !busy)
        ui.item(card, submit)
        ui.item(body, card)
        if (desiredRole == Role.EDUCATOR && !hasAccounts && !busy) {
            ui.item(body, ui.button("Crear cuentas en este celular") {
                configuring = true
                status = ""
                render()
            })
        }
    }

    private fun setupForm(body: LinearLayout) {
        val card = ui.card("Configurar celular de la educadora")
        ui.item(card, ui.label("Crea una cuenta diferente para cada rol. Usa 10 caracteres o más por contraseña.",
            color = ui.softInk))
        val educator = ui.field("Usuario educadora", TEXT, 32)
        val educatorPassword = ui.field("Contraseña educadora", PASSWORD)
        val director = ui.field("Usuario directora", TEXT, 32)
        val directorPassword = ui.field("Contraseña directora", PASSWORD)
        listOf(educator, educatorPassword, director, directorPassword).forEach { ui.item(card, it) }
        if (status.isNotEmpty()) ui.item(card, ui.label(status, color = ui.warning))
        lateinit var save: Button
        save = ui.button("Guardar cuentas", true) {
            val first = educator.text.toString()
            val firstPassword = educatorPassword.text.toString()
            val second = director.text.toString()
            val secondPassword = directorPassword.text.toString()
            hideKeyboard()
            busy = true
            ui.enable(save, false)
            background({ store.createAccounts(first, firstPassword, second, secondPassword) }) { result ->
                busy = false
                result.onSuccess {
                    hasAccounts = true
                    configuring = false
                    status = "Cuentas creadas. Ingresa como educadora."
                }.onFailure {
                    status = if (it is IllegalArgumentException) it.message.orEmpty()
                    else "No se pudieron crear las cuentas."
                }
                render()
            }
        }
        ui.enable(save, !busy)
        ui.item(card, save)
        ui.item(body, card)
        if (!busy) ui.item(body, ui.button("Volver") { configuring = false; status = ""; render() })
    }

    private fun startEducator(user: String, secret: String) {
        hideKeyboard()
        busy = true
        status = ""
        render()
        background({ store.authenticate(Role.EDUCATOR, user, secret) }) { result ->
            busy = false
            if (result.getOrDefault(false)) {
                role = Role.EDUCATOR
                expiresAt = SystemClock.elapsedRealtime() + SESSION_MS
                screen = Screen.CONNECTION
            } else status = "Acceso rechazado. Revisa las credenciales o vuelve a intentar en un minuto."
            render()
        }
    }

    private fun startDirector(user: String, secret: String, address: String) {
        val endpoint = try {
            Endpoint.parse(address)
        } catch (error: IllegalArgumentException) {
            toast(error.message.orEmpty())
            return
        }
        preferences.edit().putString("endpoint", endpoint.toString()).apply()
        hideKeyboard()
        busy = true
        status = ""
        render()
        background({
            val monitor = RemoteMonitor(endpoint)
            monitor to monitor.signIn(user, secret)
        }) { result ->
            busy = false
            result.onSuccess { (monitor, token) ->
                remote = monitor
                remoteToken = token
                role = Role.DIRECTOR
                expiresAt = SystemClock.elapsedRealtime() + SESSION_MS
                screen = Screen.MONITOR
                nextPoll = 0
            }.onFailure { error ->
                status = when (error) {
                    is Unauthorized -> "Acceso rechazado. Revisa las credenciales o espera un minuto."
                    is javax.net.ssl.SSLException -> "No se pudo establecer HTTPS. Comprueba la IP y la huella del celular A."
                    else -> "No se pudo conectar. Revisa la IP, el Wi-Fi y que A comparta el monitoreo."
                }
            }
            render()
        }
    }

    private fun signOut(message: String) {
        val oldRemote = remote
        val oldToken = remoteToken
        role = null
        remote = null
        remoteToken = null
        remoteSnapshot = Snapshot(null, false, null)
        pollFailures = 0
        polling = false
        frozen = null
        stopSource()
        stopShare()
        feed.reset()
        status = message
        render()
        if (oldRemote != null && oldToken != null) background({ oldRemote.signOut(oldToken) }) {}
    }

    private fun connectionScreen(body: LinearLayout) {
        val device = ui.card("Caracol del aula")
        connectionLabel = ui.label(bluetoothStatus, 16f, true)
        ui.item(device, connectionLabel!!)
        if (selectedDevice.isNotEmpty()) ui.item(device, ui.label(selectedDevice, color = ui.softInk))
        if (bluetooth == null) {
            ui.item(device, ui.button("Conectar Bluetooth", true, ::selectBluetooth))
            ui.item(device, ui.button("Ajustes de Bluetooth") {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            })
        } else {
            ui.item(device, ui.button("Rearmar") {
                if (!bluetoothConnected) { toast("Espera a que abra el enlace Bluetooth."); return@button }
                bluetoothReady = false
                bluetoothStatus = "Esperando confirmación del Arduino"
                actionStatus = "Esperando rearme físico."
                bluetooth?.arm()
                updateUi()
            })
            ui.item(device, ui.button("Desconectar Bluetooth") { stopSource(); render() })
        }
        ui.item(body, device)

        val share = ui.card("Segundo celular")
        ui.item(share, ui.label("Los teléfonos deben estar en la misma red Wi-Fi o en una red con acceso entre ellos.",
            color = ui.softInk))
        val current = server
        val button = if (current == null) {
            ui.button(if (shareBusy) "Iniciando enlace seguro…" else "Compartir por Wi-Fi", true, ::startShare)
        } else ui.button("Dejar de compartir") { stopShare(); render() }
        ui.enable(button, !shareBusy)
        ui.item(share, button)
        if (current != null) {
            ui.item(share, ui.label("IP y huella para la directora", 14f, true))
            ui.item(share, ui.label(current.endpoint.toString(), 13f).apply { setTextIsSelectable(true) })
            ui.item(share, ui.button("Copiar IP y huella") {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Caracol · IP y huella", current.endpoint.toString()))
                toast("IP y huella copiadas.")
            })
        }
        if (shareStatus.isNotEmpty()) ui.item(share, ui.label(shareStatus, color = ui.warning))
        ui.item(body, share)

        val demo = ui.card("Ensayo sin Arduino")
        ui.item(demo, ui.label("Los valores de este modo se identifican como simulación.", color = ui.softInk))
        ui.item(demo, ui.button(if (simulation) "Terminar simulación" else "Iniciar simulación") {
            val start = !simulation
            stopSource()
            if (start) {
                feed.reset()
                simulation = true
                simulationHidden = false
                bluetoothStatus = "Simulación activa"
                sampleSimulation()
            }
            render()
        })
        if (simulation) {
            val value = ui.label(getString(R.string.simulated_level, (simulationValue * 100 + 511) / 1023))
            ui.item(demo, value)
            ui.item(demo, SeekBar(this).apply {
                max = 1023
                progress = simulationValue
                contentDescription = "Nivel simulado de ruido"
                minimumHeight = ui.dp(48)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, current: Int, fromUser: Boolean) {
                        simulationValue = current
                        value.text = getString(R.string.simulated_level, (current * 100 + 511) / 1023)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            })
        }
        ui.item(body, demo)
    }

    private fun startShare() {
        val address = wifiAddress(this)
        if (address == null) { toast("Conecta este teléfono a una red Wi-Fi IPv4."); return }
        shareBusy = true
        shareStatus = ""
        val attempt = ++shareEpoch
        render()
        background({ MonitorServer(address, store, feed) }) { result ->
            if (attempt != shareEpoch || role != Role.EDUCATOR || !visible) {
                result.getOrNull()?.close()
                return@background
            }
            shareBusy = false
            result.onSuccess { server = it }.onFailure { shareStatus = "No se pudo abrir el monitor HTTPS." }
            render()
        }
    }

    private fun stopShare() {
        ++shareEpoch
        shareBusy = false
        shareStatus = ""
        server?.close()
        server = null
    }

    @SuppressLint("MissingPermission")
    private fun selectBluetooth() {
        if (role != Role.EDUCATOR) return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_REQUEST)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Activa Bluetooth en Ajustes.")
            return
        }
        val bonded = try {
            val preferred = preferences.getString("bluetooth", "")
            adapter.bondedDevices.sortedWith(compareByDescending<BluetoothDevice> { it.address == preferred }
                .thenBy { it.name.orEmpty() })
        } catch (_: SecurityException) {
            toast("Vuelve a conceder el permiso de Bluetooth en Ajustes.")
            return
        }
        if (bonded.isEmpty()) {
            toast("Vincula el HC-05 en Ajustes de Bluetooth.")
            return
        }
        val names = bonded.map { (it.name ?: "Dispositivo Bluetooth") + " · " + it.address.takeLast(8) }
        AlertDialog.Builder(this).setTitle("Dispositivos vinculados")
            .setItems(names.toTypedArray()) { _, index ->
                connectBluetooth(bonded[index], names[index])
            }.setNegativeButton("Cancelar", null).show()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == BLUETOOTH_REQUEST) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) selectBluetooth()
            else toast("Bluetooth necesita el permiso Dispositivos cercanos.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth(device: BluetoothDevice, displayName: String) {
        stopSource()
        feed.reset()
        selectedDevice = displayName
        val attempt = ++bluetoothEpoch
        bluetooth = try {
            BluetoothIo(device) { event ->
                main.post { if (attempt == bluetoothEpoch && visible) onBluetooth(event) }
            }
        } catch (_: Exception) {
            bluetoothStatus = "No se pudo abrir Bluetooth"
            render()
            return
        }
        preferences.edit().putString("bluetooth", device.address).apply()
        bluetoothStatus = "Conectando…"
        bluetooth?.start()
        main.postDelayed({
            if (attempt == bluetoothEpoch && bluetooth != null && !bluetoothConnected) {
                stopSource()
                bluetoothStatus = "Conexión agotada. Revisa el HC-05."
                render()
            }
        }, 10_000)
        render()
    }

    private fun onBluetooth(event: BluetoothIo.Event) {
        when (event) {
            BluetoothIo.Event.Connected -> {
                bluetoothConnected = true
                bluetoothStatus = "Vinculado · esperando al Arduino"
            }
            BluetoothIo.Event.Stale -> {
                bluetoothReady = false
                feed.disconnect()
                bluetoothStatus = "Sin lecturas recientes. Rearma el dispositivo."
                clearPending()
            }
            BluetoothIo.Event.Disconnected -> {
                stopSource()
                bluetoothStatus = "Bluetooth desconectado · estado sin confirmar"
                if (screen == Screen.CONNECTION) render()
            }
            is BluetoothIo.Event.Data -> when (val data = event.message) {
                SerialMessage.Ready -> {
                    bluetoothReady = true
                    bluetoothStatus = "En servicio"
                }
                SerialMessage.Boot -> {
                    bluetoothReady = false
                    feed.disconnect()
                    bluetoothStatus = "Arduino reiniciado. Pulsa Rearmar."
                    clearPending()
                }
                is SerialMessage.Telemetry -> {
                    if (!data.reading.armed) {
                        bluetoothReady = false
                        bluetoothStatus = "Estado seguro confirmado · rearme requerido"
                        clearPending()
                    }
                    publish(data.reading)
                }
                is SerialMessage.Ack -> if (pending == data.id) {
                    pending = null
                    actionStatus = when (data.result) {
                        "OK" -> "Orden confirmada por el Arduino."
                        "RUIDO" -> "Mostrar rechazado por nivel alto."
                        else -> "Orden rechazada. El Arduino está en estado seguro."
                    }
                }
            }
        }
        updateUi()
    }

    private fun clearPending() {
        if (pending != null) actionStatus = "Orden sin confirmación."
        pending = null
    }

    private fun stopSource() {
        ++bluetoothEpoch
        bluetooth?.close()
        bluetooth = null
        bluetoothConnected = false
        bluetoothReady = false
        bluetoothStatus = "Desconectado"
        clearPending()
        simulation = false
        feed.disconnect()
    }

    private fun sampleSimulation() {
        val alarm = simulationValue >= NOISE_THRESHOLD
        publish(Reading(++simulationSequence, simulationValue, simulationHidden || alarm, alarm, true, true))
        nextSample = SystemClock.elapsedRealtime() + 1_000
    }

    private fun publish(reading: Reading) {
        feed.publish(reading)
        writes.execute {
            runCatching { store.record(reading) }.onFailure {
                main.post { if (visible && role == Role.EDUCATOR) toast("No se pudo guardar una lectura.") }
            }
        }
    }

    private fun monitorScreen(body: LinearLayout) {
        val card = ui.card("Nivel simulado")
        monitorValue = ui.label("—", 52f, true, ui.primary)
        monitorProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ui.primary)
            contentDescription = "Porcentaje de nivel simulado"
        }
        monitorDetail = ui.label("", 16f, true)
        monitorSource = ui.label("", 14f, color = ui.softInk)
        listOf(monitorValue, monitorProgress, monitorDetail, monitorSource).forEach { ui.item(card, it!!) }
        ui.item(body, card)
        val live = Switch(this).apply {
            setText(R.string.live_mode)
            minHeight = ui.dp(48)
            isChecked = frozen == null
            setOnCheckedChangeListener { _, checked ->
                frozen = if (checked) null else currentSnapshot()
                nextPoll = 0
                updateUi()
            }
        }
        ui.item(body, live)
        ui.item(body, ui.button("Ver historial", action = ::showHistory))
    }

    private fun actuatorScreen(body: LinearLayout) {
        val card = ui.card("Orden manual")
        val radios = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        Action.entries.forEach { action ->
            radios.addView(choice(action.label).apply {
                isChecked = selectedAction == action
                setOnClickListener { selectedAction = action }
            })
        }
        ui.item(card, radios)
        sendButton = ui.button("Enviar al caracol", true, ::sendAction)
        ui.item(card, sendButton!!)
        actionLabel = ui.label(actionStatus, color = ui.softInk)
        ui.item(card, actionLabel!!)
        ui.item(body, card)
        ui.item(body, ui.label("Mostrar se admite con nivel bajo. La alerta de ruido y el estado seguro tienen prioridad.",
            color = ui.softInk))
    }

    private fun sendAction() {
        if (role != Role.EDUCATOR || !feed.snapshot().fresh || pending != null) return
        if (simulation) {
            if (selectedAction == Action.SHOW && simulationValue >= NOISE_THRESHOLD) {
                actionStatus = "Mostrar rechazado por nivel alto (simulación)."
            } else {
                simulationHidden = selectedAction == Action.HIDE
                actionStatus = "Orden confirmada por la simulación."
            }
            sampleSimulation()
        } else {
            val link = bluetooth ?: return
            if (!bluetoothReady || feed.snapshot().reading?.armed != true) return
            commandId = commandId % 60_000 + 1
            val id = commandId
            val attempt = bluetoothEpoch
            pending = id
            actionStatus = "Esperando confirmación…"
            link.send(selectedAction, id)
            main.postDelayed({
                if (attempt == bluetoothEpoch && pending == id) {
                    clearPending()
                    updateUi()
                }
            }, 2_000)
        }
        updateUi()
    }

    private fun poll() {
        val source = remote ?: return
        val token = remoteToken ?: return
        if (polling) return
        polling = true
        val started = SystemClock.elapsedRealtime()
        background({ source.snapshot(token) }) { result ->
            if (remote !== source || token != remoteToken) return@background
            polling = false
            result.onSuccess {
                remoteSnapshot = it
                remoteObservedAt = SystemClock.elapsedRealtime()
                pollFailures = 0
                nextPoll = started + 1_000
            }.onFailure {
                if (it is Unauthorized) { signOut("La sesión de monitoreo terminó."); return@background }
                remoteSnapshot = currentSnapshot().copy(online = false)
                remoteObservedAt = SystemClock.elapsedRealtime()
                pollFailures = (pollFailures + 1).coerceAtMost(4)
                nextPoll = remoteObservedAt + minOf(5_000, 1_000 * (1 shl (pollFailures - 1)))
            }
            updateUi()
        }
    }

    private fun currentSnapshot(): Snapshot {
        if (role != Role.DIRECTOR) return feed.snapshot()
        return remoteSnapshot.copy(ageMs = remoteSnapshot.ageMs?.plus(
            (SystemClock.elapsedRealtime() - remoteObservedAt).coerceAtLeast(0)
        ))
    }

    private fun showHistory() {
        val currentRole = role ?: return
        val source = remote
        val token = remoteToken
        background({
            if (currentRole == Role.EDUCATOR) store.recent()
            else requireNotNull(source).history(requireNotNull(token))
        }) { result ->
            if (!visible || role != currentRole || token != remoteToken) return@background
            result.onSuccess { rows ->
                val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                val items = rows.map { reading ->
                    val state = if (!reading.armed) "Seguro" else if (reading.hidden) "Escondido" else "Feliz"
                    formatter.format(Date(reading.capturedAt)) + " · "+ reading.percent + " % · " + state +
                        if (reading.simulated) " · Simulación" else " · Arduino"
                }
                val dialog = AlertDialog.Builder(this).setTitle("Últimas lecturas").setPositiveButton("Cerrar", null)
                if (items.isEmpty()) dialog.setMessage("Todavía no hay lecturas guardadas.")
                else dialog.setItems(items.toTypedArray(), null)
                dialog.show()
            }.onFailure {
                if (it is Unauthorized) signOut("La sesión de monitoreo terminó.")
                else toast("No se pudo consultar el historial.")
            }
        }
    }

    private fun navigation(): LinearLayout = ui.row().apply {
        setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(9))
        val entries = listOf(Screen.CONNECTION to "Conexión", Screen.MONITOR to "Monitoreo", Screen.ACTUATOR to "Activador")
        entries.forEachIndexed { index, (destination, text) ->
            ui.weighted(this, ui.button(text, destination == screen) {
                if (screen != destination) { frozen = null; screen = destination; render() }
            }.apply { isSelected = destination == screen; setPadding(ui.dp(4), 0, ui.dp(4), 0) }, if (index == 0) 0 else 6)
        }
    }

    private fun updateUi() {
        connectionLabel.display(bluetoothStatus)
        val current = frozen ?: currentSnapshot()
        val reading = current.reading
        monitorValue.display(reading?.let { it.percent.toString() + " %" } ?: "—")
        monitorProgress?.progress = reading?.percent ?: 0
        val freshness = when {
            frozen != null -> "Vista pausada"
            reading == null -> "Sin lecturas"
            !current.fresh -> "Datos desactualizados"
            else -> "Actualizado hace " + ((current.ageMs ?: 0) / 1000) + " s"
        }
        val detail = when {
            reading == null -> if (role == Role.DIRECTOR) "Esperando datos de la educadora" else "Esperando datos del caracol"
            !reading.armed -> "Caracol escondido · Estado seguro"
            else -> (if (reading.hidden) "Caracol escondido" else "Caracol feliz") +
                (if (reading.alarm) " · Nivel alto" else " · Calma")
        }
        monitorDetail.display(if (reading != null && !current.fresh) "Último estado: $detail" else detail)
        val detailColor = if (reading?.alarm == true) ui.warning else ui.positive
        if (monitorDetail?.currentTextColor != detailColor) monitorDetail?.setTextColor(detailColor)
        monitorSource.display(freshness + when {
            reading?.simulated == true -> " · Simulación"
            reading != null -> " · Arduino"
            else -> ""
        })
        actionLabel.display(actionStatus)
        val local = feed.snapshot()
        ui.enable(sendButton, role == Role.EDUCATOR && pending == null && local.fresh &&
            (simulation || (bluetoothReady && local.reading?.armed == true)))
    }

    private fun TextView?.display(value: String) {
        if (this != null && text.toString() != value) text = value
    }

    private fun choice(label: String) = RadioButton(this).apply {
        id = View.generateViewId()
        text = label
        minHeight = ui.dp(48)
        setTextColor(ui.ink)
    }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val result = runCatching(work)
            main.post {
                if (!isDestroyed) done(result)
                else (result.getOrNull() as? MonitorServer)?.close()
            }
        }
    }

    companion object {
        private const val BLUETOOTH_REQUEST = 10
        private const val TEXT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        private const val PASSWORD = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
}
