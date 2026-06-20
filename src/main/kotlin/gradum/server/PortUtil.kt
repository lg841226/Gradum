package gradum.server

import java.net.InetSocketAddress
import java.net.Socket

fun isPortAvailable(checkPort: Int, hostAddress: String = "localhost"): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(hostAddress, checkPort), 500)
            false
        }
    } catch (e: Exception) {
        true
    }
}

fun findAvailablePort(startPort: Int, maxAttempts: Int = 10, hostAddress: String = "localhost"): Int {
    for (port in startPort until startPort + maxAttempts) {
        if (isPortAvailable(port, hostAddress)) {
            return port
        }
    }
    throw RuntimeException("No available port in range $startPort-${startPort + maxAttempts}")
}
