package com.ranaco.wheel

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.LockSupport
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The UDP link to wheel_companion.py on the PC.
 *
 * Sends the controls at a fixed [RATE_HZ] whether or not anything changed: the
 * companion lets go of everything once packets stop, so silence must only ever mean
 * the phone is gone. Talks straight to the PC it found last time, and looks for it by
 * broadcast until it answers, unless an address is set.
 */
class Link(
    context: Context,
    private val settings: Settings,
    private val controls: Controls,
    private val onRumble: (large: Int, small: Int) -> Unit,
    /** What the PC's page calls this phone. */
    deviceName: String,
) {
    // Who this phone is, the same every time, so the PC keeps its controller through restarts.
    private val phoneId = settings.phoneId
    private val name = deviceName.toByteArray(Charsets.UTF_8).take(NAME_SIZE).toByteArray()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    // Main thread only.
    private var session: Session? = null
    private var address = ""

    /** The PC's name once it has answered. */
    val pcName get() = session?.pcName
    /** Which player the PC made this phone, 1 to 4; 0 until it says. */
    val player get() = session?.player?.takeIf { it <= 4 } ?: 0
    /** The PC has all its controllers taken and turned this phone away. */
    val full get() = connected && session?.player == FULL
    /** The PC hasn't let this phone in yet: someone has to click Allow there, or scan its code. */
    val waiting get() = connected && session?.player == WAITING
    /** The PC turned this phone down. */
    val blocked get() = connected && session?.player == BLOCKED
    val connected get() = session?.connected == true
    /** Per [Button], the Xbox button it presses on this PC (see [Xbox]); null until the PC says. */
    val xbox get() = session?.xbox

    /** Main thread. */
    fun start(pcAddress: String) {
        if (session != null) return
        address = pcAddress.trim()
        session = Session(address).also { it.start() }
    }

    /** Main thread. Lets the PC know right away, so it releases everything without waiting for the timeout. */
    fun stop() {
        session?.stop()
        session = null
    }

    /**
     * One socket and its two threads. Every start gets a new one, so a session that's
     * still winding down can never carry on beside its replacement.
     */
    private inner class Session(private val pcAddress: String) {
        @Volatile private var running = true
        @Volatile var pcName: String? = null
            private set
        @Volatile var player = 0
            private set
        @Volatile var xbox: ByteArray? = null
            private set
        @Volatile private var lastHeard = 0L
        @Volatile private var target: InetSocketAddress? = null
        private var manual: InetSocketAddress? = null
        private val began = SystemClock.elapsedRealtime()
        private val socket = DatagramSocket().apply { broadcast = true }
        private lateinit var sender: Thread

        val connected get() = running && SystemClock.elapsedRealtime() - lastHeard < 1500

        fun start() {
            sender = thread(name = "wheel-send") { send() }
            thread(name = "wheel-receive", isDaemon = true) { receive() }
        }

        fun stop() {
            running = false
            LockSupport.unpark(sender)
        }

        private fun send() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            manual = resolve(pcAddress)
            // The PC found last time gets the controls straight away; discovery still runs in case it moved.
            target = manual ?: resolve(settings.lastPc)
            val state = ByteBuffer.allocate(STATE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val packet = DatagramPacket(state.array(), STATE_SIZE)
            var seq = 0
            var lastDiscover = 0L
            var stale = false
            val period = 1_000_000_000L / RATE_HZ
            var next = System.nanoTime()

            while (running) {
                val now = SystemClock.elapsedRealtime()
                val silent = now - max(lastHeard, began)
                // Answers can stop reaching a socket while sending still works (seen on the
                // Razr, fixed by reopening the screen). A new socket on a new port gets them again.
                if (silent > STALE_MS) {
                    stale = true
                    break
                }
                // Look for the PC until it answers, and again if it goes quiet (its address may have changed).
                if (now - lastHeard > 2000 && now - lastDiscover > 1000) {
                    lastDiscover = now
                    discover()
                } else if (now - lastDiscover > ANNOUNCE_MS) {
                    // Connected: say who this is again, to the PC only, so a restarted
                    // companion learns this phone's name without waiting for a dropout.
                    lastDiscover = now
                    target?.let { pc ->
                        val hello = hello(proof = true)
                        runCatching { socket.send(DatagramPacket(hello, hello.size, pc)) }
                    }
                }
                target?.let {
                    seq++
                    state.clear()
                    state.put('R'.code.toByte()).put('W'.code.toByte()).put(VERSION).put(T_STATE)
                    state.putInt(seq)
                    state.putShort((controls.steer.coerceIn(-1f, 1f) * 32767).roundToInt().toShort())
                    state.putShort((controls.throttle.coerceIn(0f, 1f) * 65535).roundToInt().toShort())
                    state.putShort((controls.brake.coerceIn(0f, 1f) * 65535).roundToInt().toShort())
                    state.putInt(controls.buttons)
                    // Then who's sending, and the rest of the sticks (drone mode's): left Y, right X, right Y.
                    state.put(phoneId)
                    state.putShort(stick(controls.leftY)).putShort(stick(controls.rightX)).putShort(stick(controls.rightY))
                    packet.socketAddress = it
                    runCatching { socket.send(packet) }
                }
                next += period
                val wait = next - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait) else next = System.nanoTime()
            }

            // A stale session hands straight over without a goodbye: the controls keep flowing,
            // and the companion takes the new port from the same phone at once.
            if (!stale) target?.let { pc ->
                val bye = header(T_BYE)
                repeat(3) { runCatching { socket.send(DatagramPacket(bye, bye.size, pc)) } }
            }
            socket.close()
            if (stale) main.post {
                if (session === this) {
                    session = null
                    start(address)
                }
            }
        }

        private fun discover() {
            // The PC itself, if it's known, hears that this phone scanned its code; a broadcast,
            // which anyone on the Wi-Fi can hear, only says who's asking.
            val known = manual ?: target
            known?.let { pc ->
                val hello = hello(proof = true)
                runCatching { socket.send(DatagramPacket(hello, hello.size, pc)) }
            }
            // With an address typed in, nobody else's answer counts, so nobody else is asked.
            if (manual != null) return
            val hello = hello(proof = false)
            listOfNotNull(InetSocketAddress(InetAddress.getByName("255.255.255.255"), PORT),
                subnetBroadcast()?.let { InetSocketAddress(it, PORT) })
                .forEach { runCatching { socket.send(DatagramPacket(hello, hello.size, it)) } }
        }

        private fun receive() {
            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)
            while (!socket.isClosed) {
                try {
                    packet.setLength(buffer.size)
                    socket.receive(packet)
                } catch (_: Exception) {
                    break
                }
                val n = packet.length
                if (n < 4 || buffer[0] != 'R'.code.toByte() || buffer[1] != 'W'.code.toByte() || buffer[2] != VERSION) continue
                val from = packet.socketAddress as InetSocketAddress
                when (buffer[3]) {
                    T_HELLO -> {
                        // The PC answering a discovery. If an address was typed in, only that one
                        // counts, port and all: two companions can share a PC (and the emulator's host).
                        if (manual.let { it == null || it.address == from.address && it.port == from.port }) {
                            target = InetSocketAddress(from.address, from.port)
                            pcName = String(buffer, 4, n - 4, Charsets.UTF_8)
                            lastHeard = SystemClock.elapsedRealtime()
                            from.address.hostAddress?.let {
                                val found = if (from.port == PORT) it else "$it:${from.port}"
                                if (found != settings.lastPc) settings.lastPc = found
                            }
                        }
                    }
                    T_STATUS -> if (n >= 6 && from.address == target?.address) {
                        lastHeard = SystemClock.elapsedRealtime()
                        if (n >= 7) player = buffer[6].toInt() and 0xFF
                        onRumble(buffer[4].toInt() and 0xFF, buffer[5].toInt() and 0xFF)
                    }
                    // Comes with every hello; only a change is worth a new array (and a redraw).
                    T_BUTTONS -> if (from.address == target?.address) {
                        val codes = buffer.copyOfRange(4, n)
                        if (!codes.contentEquals(xbox)) xbox = codes
                    }
                }
            }
        }
    }

    /** "192.168.1.6", or with a port, "192.168.1.6:47900". */
    private fun resolve(address: String): InetSocketAddress? = address.takeIf { it.isNotEmpty() }?.let {
        val port = it.substringAfterLast(':', "").toIntOrNull()
        val host = if (port != null) it.substringBeforeLast(':') else it
        runCatching { InetSocketAddress(InetAddress.getByName(host), port ?: PORT) }.getOrNull()
    }

    /** Some routers drop 255.255.255.255; the subnet's own broadcast address gets through. */
    private fun subnetBroadcast(): InetAddress? {
        val link = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
        val v4 = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val bytes = v4.address.address
        val ip = ByteBuffer.wrap(bytes).int
        val mask = if (v4.prefixLength == 0) 0 else -1 shl (32 - v4.prefixLength)
        return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip or mask.inv()).array())
    }

    private fun header(type: Byte) = byteArrayOf('R'.code.toByte(), 'W'.code.toByte(), VERSION, type)

    private fun stick(value: Float) = (value.coerceIn(-1f, 1f) * 32767).roundToInt().toShort()

    /**
     * Who this phone is: its id and name, and (with [proof]) that it scanned the last PC code
     * it saw. The proof is that code's token keyed with this phone's id, so the token itself is
     * never sent, and a proof overheard is no use to another phone. With one, the name is
     * padded to its full length, so the PC can find the proof after it.
     */
    private fun hello(proof: Boolean): ByteArray {
        val token = settings.pairToken
        if (!proof || token.isEmpty()) return header(T_DISCOVER) + phoneId + name
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        return header(T_DISCOVER) + phoneId + name.copyOf(NAME_SIZE) + mac.doFinal(phoneId).copyOf(PROOF_SIZE)
    }

    companion object {
        const val PORT = 47800
        const val RATE_HZ = 125
        /** No answer from the PC for this long and the session starts over on a new socket. */
        private const val STALE_MS = 6000L
        private const val ANNOUNCE_MS = 5000L
        /** What the PC sends instead of a player number: it's full, this phone waits to be let in, or it was turned down. */
        private const val FULL = 0xFF
        private const val WAITING = 0xFE
        private const val BLOCKED = 0xFD
        private const val NAME_SIZE = 64
        private const val PROOF_SIZE = 16
        private const val VERSION: Byte = 1
        private const val T_DISCOVER: Byte = 0x01
        private const val T_STATE: Byte = 0x02
        private const val T_BYE: Byte = 0x03
        private const val T_HELLO: Byte = 0x81.toByte()
        private const val T_STATUS: Byte = 0x82.toByte()
        private const val T_BUTTONS: Byte = 0x83.toByte()
        // magic, version, type, seq, steer, throttle, brake, buttons, then phone id and three sticks
        private const val STATE_SIZE = 2 + 1 + 1 + 4 + 2 + 2 + 2 + 4 + 8 + 2 + 2 + 2
    }
}
