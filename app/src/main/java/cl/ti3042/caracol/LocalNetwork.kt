package cl.ti3042.caracol

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import org.json.JSONArray
import org.json.JSONObject

private const val PORT = 8765
private const val TLS_ALIAS = "caracol-monitor-ec-v2"

data class Endpoint(val ip: String, val pin: String) {
    override fun toString() = "$ip#$pin"

    companion object {
        fun parse(text: String): Endpoint {
            val parts = text.trim().split('#')
            require(parts.size == 2) { "Ingresa IP#huella tal como aparece en el celular de la educadora." }
            val octets = parts[0].split('.')
            val pin = parts[1].uppercase(Locale.ROOT)
            require(octets.size == 4 && octets.all {
                it.length in 1..3 && it.all { char -> char in '0'..'9' } && (it.toIntOrNull() ?: -1) in 0..255
            } && pin.matches(Regex("[A-F0-9]{64}"))) { "Revisa la dirección IP y la huella de 64 caracteres." }
            return Endpoint(octets.joinToString(".") { it.toInt().toString() }, pin)
        }
    }
}

@Suppress("DEPRECATION")
fun wifiAddress(context: Context): Inet4Address? {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    return manager.allNetworks.firstNotNullOfOrNull { network ->
        if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            return@firstNotNullOfOrNull null
        }
        manager.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.filterIsInstance<Inet4Address>()?.firstOrNull()
    }
}

class MonitorServer(address: Inet4Address, private val store: DataStore, private val feed: LiveFeed) : AutoCloseable {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val workers = ThreadPoolExecutor(2, 4, 20, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private val server: SSLServerSocket
    val endpoint: Endpoint

    init {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keys.containsAlias(TLS_ALIAS)) {
            val now = System.currentTimeMillis()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(TLS_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt can hash the handshake itself before requesting a signature.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=Caracol Interactivo"))
                    .setCertificateNotBefore(Date(now - 86_400_000))
                    .setCertificateNotAfter(Date(now + 5 * 365 * 86_400_000L))
                    .build())
            }.generateKeyPair()
        }
        val certificate = keys.getCertificate(TLS_ALIAS) as X509Certificate
        val identity = MonitorIdentity(keys.getKey(TLS_ALIAS, null) as PrivateKey, certificate)
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(identity), null, random) }
        endpoint = Endpoint(requireNotNull(address.hostAddress), fingerprint(certificate))
        server = (ssl.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            try {
                reuseAddress = true
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                bind(InetSocketAddress(address, PORT), 8)
            } catch (error: Exception) {
                close()
                throw error
            }
        }
        thread("Caracol HTTPS") {
            while (!server.isClosed) {
                val connection = try {
                    server.accept() as SSLSocket
                } catch (_: IOException) {
                    break
                }
                sockets.add(connection)
                try {
                    workers.execute {
                        try {
                            connection.use(::serve)
                        } catch (_: Exception) {
                            // A malformed, expired or interrupted request closes its connection.
                        } finally {
                            sockets.remove(connection)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    sockets.remove(connection)
                    runCatching(connection::close)
                }
            }
        }
    }

    private fun serve(socket: SSLSocket) {
        socket.soTimeout = 4_000
        try {
            socket.startHandshake()
        } catch (error: javax.net.ssl.SSLException) {
            android.util.Log.w("CaracolTLS", "Error al negociar HTTPS", error)
            throw error
        }
        val input = BufferedInputStream(socket.inputStream)
        val request = asciiLine(input, 300).split(' ')
        if (request.size != 3 || request[2] != "HTTP/1.1") return respond(socket, 400)
        val headers = mutableMapOf<String, String>()
        repeat(24) {
            val line = asciiLine(input, 1024)
            if (line.isEmpty()) {
                return dispatch(socket, input, request[0], request[1], headers)
            }
            val separator = line.indexOf(':')
            if (separator < 1) return respond(socket, 400)
            val name = line.substring(0, separator).lowercase(Locale.ROOT)
            if (headers.put(name, line.substring(separator + 1).trim()) != null) return respond(socket, 400)
        }
        respond(socket, 400)
    }

    private fun dispatch(socket: SSLSocket, input: InputStream, method: String, path: String, headers: Map<String, String>) {
        val length = headers["content-length"]?.toIntOrNull() ?: if (headers.containsKey("content-length")) -1 else 0
        if (length !in 0..2048 || headers.containsKey("transfer-encoding")) return respond(socket, 400)
        val now = SystemClock.elapsedRealtime()
        sessions.entries.removeIf { it.value <= now }
        if (method == "POST" && path == "/login") {
            val payload = ByteArray(length).also { DataInputStream(input).readFully(it) }
            val login = try {
                JSONObject(payload.toString(Charsets.UTF_8))
            } catch (_: org.json.JSONException) {
                return respond(socket, 400)
            }
            if (!store.authenticate(Role.DIRECTOR, login.optString("user"), login.optString("password"))) {
                return respond(socket, 401)
            }
            val token = Base64.encodeToString(ByteArray(32).also(random::nextBytes),
                Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
            if (sessions.size >= 12) return respond(socket, 503)
            sessions[token] = now + SESSION_MS
            return respond(socket, 200, JSONObject().put("token", token))
        }
        val auth = headers["authorization"] ?: return respond(socket, 401)
        val token = auth.removePrefix("Bearer ")
        if (auth == token || (sessions[token] ?: 0L) <= now) return respond(socket, 401)
        when {
            method == "GET" && path == "/snapshot" -> respond(socket, 200, feed.snapshot().json())
            method == "GET" && path == "/history" -> respond(socket, 200,
                JSONObject().put("readings", JSONArray(store.recent().map(Reading::json))))
            method == "POST" && path == "/logout" -> {
                sessions.remove(token)
                respond(socket, 200)
            }
            else -> respond(socket, 404)
        }
    }

    override fun close() {
        runCatching(server::close)
        sockets.forEach { runCatching(it::close) }
        workers.shutdownNow()
        sessions.clear()
    }
}

private class MonitorIdentity(private val key: PrivateKey, private val certificate: X509Certificate) :
    X509ExtendedKeyManager() {
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (keyType == key.algorithm) arrayOf(TLS_ALIAS) else null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        getServerAliases(keyType, issuers)?.firstOrNull()

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        chooseServerAlias(keyType, issuers, null)

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == TLS_ALIAS) key else null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == TLS_ALIAS) arrayOf(certificate) else null

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}

class RemoteMonitor(private val endpoint: Endpoint) {
    private val ssl = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(PinnedCertificate(endpoint.pin)), null)
    }

    fun signIn(user: String, password: String): String = call("/login",
        payload = JSONObject().put("user", user).put("password", password)).getString("token")

    fun snapshot(token: String) = Snapshot.fromJson(call("/snapshot", token))
    fun history(token: String) = Reading.listFromJson(call("/history", token).getJSONArray("readings"))
    fun signOut(token: String) { call("/logout", token, JSONObject()) }

    private fun call(path: String, token: String? = null, payload: JSONObject? = null): JSONObject {
        val connection = URL("https://"+endpoint.ip+":"+PORT+path).openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = ssl.socketFactory
            // The out-of-band certificate fingerprint identifies this server independently of its changing LAN IP.
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
                host == endpoint.ip && runCatching {
                    matchesPin(session.peerCertificates.first() as X509Certificate, endpoint.pin)
                }.getOrDefault(false)
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_500
            connection.readTimeout = 3_500
            connection.useCaches = false
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (payload != null) {
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            when (connection.responseCode) {
                200 -> return connection.inputStream.use { JSONObject(readLimited(it, 16_384)) }
                401 -> throw Unauthorized()
                else -> throw IOException("El servidor no pudo completar la solicitud.")
            }
        } finally {
            connection.disconnect()
        }
    }
}

class Unauthorized : IOException()

private class PinnedCertificate(private val expected: String) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("No se emplean certificados de cliente.")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val server = chain.firstOrNull() ?: throw CertificateException("Servidor sin certificado.")
        server.checkValidity()
        if (!matchesPin(server, expected)) throw CertificateException("La huella del servidor no coincide.")
    }
}

private fun matchesPin(certificate: X509Certificate, expected: String) =
    MessageDigest.isEqual(fingerprint(certificate).toByteArray(), expected.toByteArray())

private fun fingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }.uppercase(Locale.ROOT)

private fun respond(socket: SSLSocket, status: Int, json: JSONObject = JSONObject()) {
    val reason = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        else -> "Service Unavailable"
    }
    val bytes = json.toString().toByteArray(Charsets.UTF_8)
    val output = socket.outputStream
    output.write(("HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
        "Content-Length: "+bytes.size+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
    output.write(bytes)
    output.flush()
}

private fun asciiLine(input: InputStream, max: Int): String {
    val output = StringBuilder()
    repeat(max + 1) {
        val char = input.read()
        if (char < 0) throw EOFException()
        if (char == 13) {
            if (input.read() != 10) throw IOException("Línea HTTP inválida.")
            return output.toString()
        }
        if (char !in 32..126 && char != 9) throw IOException("Cabecera HTTP inválida.")
        output.append(char.toChar())
    }
    throw IOException("Cabecera HTTP demasiado larga.")
}

private fun readLimited(input: InputStream, max: Int): String {
    val buffer = ByteArray(2048)
    val output = ByteArrayOutputStream()
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toString("UTF-8")
        if (output.size() + count > max) throw IOException("Respuesta demasiado larga.")
        output.write(buffer, 0, count)
    }
}

internal fun thread(name: String, block: () -> Unit) = Thread(block, name).apply {
    isDaemon = true
    start()
}
