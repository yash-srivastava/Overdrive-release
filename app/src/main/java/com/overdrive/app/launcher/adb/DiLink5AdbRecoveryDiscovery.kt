package com.overdrive.app.launcher.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Resolves one on-device TLS ADB endpoint and rejects unidentified network state. */
class DiLink5AdbRecoveryDiscovery(private val context: Context) {
    data class NetworkIdentity(
        val id: String,
        val addresses: Set<InetAddress>,
    )

    data class Endpoint(val address: InetAddress, val port: Int, val networkId: String)

    sealed class Result {
        data class Found(val endpoint: Endpoint) : Result()
        data object NoValidatedWifi : Result()
        data object NoListener : Result()
        data object Cancelled : Result()
    }

    fun currentNetwork(): NetworkIdentity? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return null
        val addresses = manager.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }?.toSet().orEmpty()
        if (addresses.isEmpty()) return null

        val transportWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            capabilities.transportInfo as? WifiInfo
        } else {
            null
        }
        @Suppress("DEPRECATION")
        val legacyWifi = try {
            context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        } catch (_: Throwable) {
            null
        }
        val id = stableNetworkId(transportWifi?.ssid, transportWifi?.bssid)
            ?: stableNetworkId(legacyWifi?.ssid, legacyWifi?.bssid)
            ?: ephemeralNetworkId(
            network.networkHandle,
            manager.getLinkProperties(network)?.interfaceName,
            addresses,
        )
        return NetworkIdentity(id, addresses)
    }

    fun discover(timeoutMs: Long, cancelled: AtomicBoolean, includeProperty: Boolean = true): Result {
        if (cancelled.get()) return Result.Cancelled
        val network = currentNetwork() ?: return Result.NoValidatedWifi
        if (includeProperty) {
            propertyPort()?.let { port ->
                val address = network.addresses.firstOrNull { !it.isLoopbackAddress }
                    ?: InetAddress.getLoopbackAddress()
                return Result.Found(Endpoint(address, port, network.id))
            }
            return Result.NoListener
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Result.NoListener
        val manager = context.getSystemService(NsdManager::class.java) ?: return Result.NoListener
        val terminal = CountDownLatch(1)
        val result = AtomicReference<Endpoint?>()
        val resolving = AtomicBoolean(false)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = terminal.countDown()
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = terminal.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = terminal.countDown()
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (cancelled.get() || !resolving.compareAndSet(false, true)) return
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving.set(false)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host
                        if (host != null && serviceInfo.port in 1..65535 &&
                            isCandidateAllowed(host, network)) {
                            result.compareAndSet(null, Endpoint(host, serviceInfo.port, network.id))
                            terminal.countDown()
                        } else {
                            resolving.set(false)
                        }
                    }
                })
            }
        }
        return try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1))
            while (!cancelled.get() && result.get() == null && System.nanoTime() < deadline) {
                if (terminal.await(50, TimeUnit.MILLISECONDS)) break
            }
            when {
                cancelled.get() -> Result.Cancelled
                result.get() != null -> Result.Found(result.get()!!)
                else -> Result.NoListener
            }
        } finally {
            try { manager.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
    }

    private fun propertyPort(): Int? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val value = clazz.getMethod("get", String::class.java)
            .invoke(null, TLS_PORT_PROPERTY) as? String
        value?.toIntOrNull()?.takeIf { it in 1..65535 }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val SERVICE_TYPE = "_adb-tls-connect._tcp."
        private const val TLS_PORT_PROPERTY = "service.adb.tls.port"

        @JvmStatic
        fun stableNetworkId(ssid: String?, bssid: String?): String? {
            val normalizedSsid = ssid?.trim()?.removeSurrounding("\"")
            val normalizedBssid = bssid?.trim()?.lowercase()
            if (normalizedSsid.isNullOrBlank() || normalizedSsid == "<unknown ssid>" ||
                normalizedBssid.isNullOrBlank() || normalizedBssid == "02:00:00:00:00:00") return null
            return MessageDigest.getInstance("SHA-256")
                .digest("$normalizedSsid\u0000$normalizedBssid".toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }

        @JvmStatic
        fun ephemeralNetworkId(
            networkHandle: Long,
            interfaceName: String?,
            addresses: Set<InetAddress>,
        ): String {
            val addressKey = addresses.mapNotNull { it.hostAddress }.sorted().joinToString(",")
            val seed = "boot:$networkHandle\u0000${interfaceName.orEmpty()}\u0000$addressKey"
            return "ephemeral:" + MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }

        @JvmStatic
        fun isCandidateAllowed(address: InetAddress, network: NetworkIdentity): Boolean =
            network.addresses.contains(address) && AdbTlsTransport.isLocalAddress(address)
    }
}
