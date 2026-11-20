package com.purelock.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.db.SecurityEventType
import com.purelock.app.data.prefs.AppSettings
import com.purelock.app.data.prefs.DnsProvider
import com.purelock.app.util.Motivation
import com.purelock.app.util.Notifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local DNS-filtering VPN.
 *
 * Only the virtual DNS address is routed into the tunnel, so ordinary traffic does NOT pass through
 * this app. Each DNS query is checked on the device against the local filter: blocked names get an
 * "NXDOMAIN" answer, everything else is forwarded to the chosen upstream resolver. HTTPS traffic is never
 * inspected and no certificate is installed. Browsing data is not sent to any PureLock server.
 *
 * Limits (also stated in the UI): browsers using their own encrypted DNS ("Secure DNS"/DoH), another
 * VPN, or a proxy can bypass DNS filtering.
 */
class PureLockVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var settingsJob: Job? = null
    private var pool: ExecutorService? = null

    @Volatile private var running = false
    @Volatile private var settings = AppSettings()
    private val filter = DnsFilter(
        isBlocked = { Graph.blocklist.isBlocked(it) },
        safeSearchEnabled = { settings.safeSearch }
    )

    private val lastBlockLogged = ConcurrentHashMap<String, Long>()
    @Volatile private var lastNotification = 0L
    @Volatile private var cachedSystemDns: List<InetAddress> = emptyList()
    @Volatile private var cachedSystemDnsAt = 0L
    private val random = Random()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!running) startTunnel()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = Graph.notifier.vpnServiceNotification(notifyAsTest)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_VPN, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(Notifier.ID_VPN, notification)
        }
    }

    private fun startTunnel() {
        try {
            val builder = Builder()
                .setSession("PureLock")
                .setMtu(1500)
                .addAddress(CLIENT_IP, 24)
                .addDnsServer(DNS_VIP)
                .addRoute(DNS_VIP, 32) // only DNS goes into the tunnel
                .setBlocking(true)
            val descriptor = builder.establish()
            if (descriptor == null) {
                // Permission was revoked or never granted.
                VpnState.lastError = "VPN permission missing"
                stopSelf()
                return
            }
            tun = descriptor
        } catch (e: Exception) {
            VpnState.lastError = e.message
            stopSelf()
            return
        }

        running = true
        VpnState.lastError = null
        VpnState.setRunning(true)
        pool = Executors.newFixedThreadPool(4)

        settingsJob = Graph.appScope.launch {
            Graph.settings.flow.collect { settings = it }
        }

        readerThread = Thread({ readLoop() }, "purelock-dns").also { it.start() }
    }

    private fun readLoop() {
        val fd = tun?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val buffer = ByteArray(32_767)
        val vip = ipBytes(DNS_VIP)
        try {
            while (running) {
                val n = input.read(buffer)
                if (n <= 0) continue
                val packet = IpUdp.parse(buffer, n) ?: continue
                if (packet.dstPort != 53 || !packet.dstIp.contentEquals(vip)) continue
                pool?.execute { handleQuery(packet, output) }
            }
        } catch (_: IOException) {
            // The tunnel was closed (stop or revoke): leave the loop.
        }
    }

    private fun handleQuery(packet: IpUdp.Packet, output: FileOutputStream) {
        VpnState.lastQueryEpoch = System.currentTimeMillis()
        val query = packet.payload
        val question = DnsPacket.parseQuestion(query, query.size)

        val reply: ByteArray? = if (question == null) {
            forward(query)
        } else {
            when (val decision = filter.decide(question.name)) {
                DnsDecision.Block -> {
                    onBlocked(question.name)
                    DnsPacket.buildNxDomain(query, question.end)
                }
                is DnsDecision.Rewrite -> rewrite(query, question, decision.target)
                DnsDecision.Forward -> forward(query)
            }
        }
        if (reply == null) return
        val response = IpUdp.build(packet.dstIp, packet.srcIp, 53, packet.srcPort, reply)
        try {
            synchronized(output) { output.write(response) }
        } catch (_: IOException) {
            // Tunnel closed while answering.
        }
    }

    private fun rewrite(query: ByteArray, question: DnsPacket.Question, target: String): ByteArray? =
        when (question.type) {
            DnsPacket.TYPE_A -> {
                val resolved = forward(DnsPacket.buildQuery(target, DnsPacket.TYPE_A, random))
                val ips = resolved?.let { DnsPacket.extractARecords(it, it.size) } ?: emptyList()
                if (ips.isEmpty()) forward(query) else DnsPacket.buildARecords(query, question.end, ips, 300)
            }
            // The safe endpoints are answered over IPv4 only; telling the client "no AAAA/HTTPS
            // record" makes it use the IPv4 address.
            DnsPacket.TYPE_AAAA, DnsPacket.TYPE_HTTPS -> DnsPacket.buildEmpty(query, question.end)
            else -> forward(query)
        }

    /** Sends [query] to the upstream resolver(s) outside the tunnel and returns the raw response. */
    private fun forward(query: ByteArray): ByteArray? {
        for (server in upstreamServers()) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket) // keep this socket out of the VPN
                    socket.soTimeout = 4_000
                    socket.send(DatagramPacket(query, query.size, server, 53))
                    val buf = ByteArray(4_096)
                    val response = DatagramPacket(buf, buf.size)
                    socket.receive(response)
                    return buf.copyOf(response.length)
                }
            } catch (_: IOException) {
                // try the next server
            }
        }
        return null
    }

    private fun upstreamServers(): List<InetAddress> {
        val provider = settings.dnsProvider
        if (provider != DnsProvider.SYSTEM) {
            return provider.servers.map { InetAddress.getByName(it) }
        }
        val now = System.currentTimeMillis()
        if (cachedSystemDns.isNotEmpty() && now - cachedSystemDnsAt < 30_000) return cachedSystemDns
        val found = systemDnsServers()
        // If the network's resolver cannot be determined, fall back to Cloudflare's plain resolver.
        val servers = found.ifEmpty { listOf(InetAddress.getByName("1.1.1.1")) }
        cachedSystemDns = servers
        cachedSystemDnsAt = now
        return servers
    }

    private fun systemDnsServers(): List<InetAddress> {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val servers = cm.getLinkProperties(network)?.dnsServers.orEmpty()
                .filter { it is java.net.Inet4Address }
            if (servers.isNotEmpty()) return servers
        }
        return emptyList()
    }

    private fun onBlocked(name: String) {
        val now = System.currentTimeMillis()
        // Browsers ask A, AAAA and HTTPS records at once; count a blocked site only once per 5 seconds.
        val last = lastBlockLogged[name]
        if (last != null && now - last < 5_000) return
        lastBlockLogged[name] = now
        if (lastBlockLogged.size > 500) lastBlockLogged.clear()

        Graph.appScope.launch {
            // Blocks during a test lock are trial blocks: they do not count in real statistics.
            if (!Graph.lockManager.isTestLock()) Graph.stats.recordWebsiteBlock(now)
        }

        if (now - lastNotification > 30_000) {
            lastNotification = now
            Graph.notifier.event(
                Notifier.ID_WEB_BLOCK,
                getString(R.string.notif_blocked_site_title),
                Motivation.random(this)
            )
        }
    }

    // ------------------------------------------------------------ lifecycle

    /** The user (or Android) revoked the VPN permission: filtering stopped. Say so honestly. */
    override fun onRevoke() {
        Graph.appScope.launch {
            Graph.eventLog.log(SecurityEventType.VPN_REVOKED, "")
            if (Graph.lockManager.isActive() && !Graph.lockManager.isTestLock()) Graph.stats.recordInterruption()
            if (Graph.settings.get().interruptionAlerts) {
                Graph.notifier.alert(
                    Notifier.ID_INTEGRITY,
                    getString(R.string.notif_filter_stopped_title),
                    getString(R.string.notif_filter_stopped_text)
                )
            }
        }
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        running = false
        VpnState.setRunning(false)
        settingsJob?.cancel()
        settingsJob = null
        pool?.shutdownNow()
        pool = null
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        readerThread?.interrupt()
        readerThread = null
    }

    companion object {
        private const val CLIENT_IP = "10.111.222.1"
        private const val DNS_VIP = "10.111.222.2"

        /** Whether the ongoing VPN notification should say TEST. Set by [start], cleared by [stop]. */
        @Volatile private var notifyAsTest = false

        private fun ipBytes(ip: String): ByteArray = ip.split('.').map { it.toInt().toByte() }.toByteArray()

        fun isRunning(): Boolean = VpnState.running.value

        /**
         * Starts the filter. Returns false if Android refused (for example a background start
         * restriction), so the caller can tell the user instead of pretending it works.
         * Requires that the user already granted VPN consent ([VpnService.prepare] returned null).
         * [isTest] marks the ongoing notification as a test-mode trial lock.
         */
        fun start(context: Context, isTest: Boolean = false): Boolean = try {
            notifyAsTest = isTest
            ContextCompat.startForegroundService(context, Intent(context, PureLockVpnService::class.java))
            true
        } catch (e: Exception) {
            VpnState.lastError = e.message
            false
        }

        fun stop(context: Context) {
            notifyAsTest = false
            context.stopService(Intent(context, PureLockVpnService::class.java))
        }
    }
}
