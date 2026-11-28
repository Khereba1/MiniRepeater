package com.khereba1.app

import java.io.BufferedReader
import java.io.InputStreamReader

data class ShellResult(val exitCode: Int, val output: String)

object RootRouter {
    const val LEASE_FILE = "/data/local/tmp/minirepeater_dhcp.leases"
    const val PID_FILE = "/data/local/tmp/minirepeater_dnsmasq.pid"

    private val CANDIDATE_OCTETS = listOf(49, 50, 51, 52)

    @Volatile var activeOctet: Int = 49
        private set
    @Volatile var activeGatewayIp: String = "192.168.49.1"
        private set
    @Volatile var activeSubnet: String = "192.168.49.0/24"
        private set
    @Volatile var activeVpnIface: String? = null
        private set
    @Volatile var activeOutIface: String = "wlan0"
        private set

    fun runRoot(script: String): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val code = process.waitFor()
            ShellResult(code, out.trim())
        } catch (e: Exception) {
            ShellResult(-1, e.message ?: "su failed")
        }
    }

    fun detectUpstream(): String {
        val cmd = """ip route get 8.8.8.8 2>/dev/null | sed -n 's/.*dev \([^ ]*\).*/\1/p' | head -n 1"""
        val res = runRoot(cmd)
        val dev = res.output.trim()
        return if (res.exitCode == 0 && dev.isNotBlank() && !dev.startsWith("tun")) dev else "wlan0"
    }

    fun detectVpnInterface(): String? {
        val res = runRoot("ip -o -4 addr show 2>/dev/null | grep -oE 'tun[0-9]+' | head -n 1")
        val dev = res.output.trim()
        return if (res.exitCode == 0 && dev.isNotBlank()) dev else null
    }

    fun selectNonCollidingSubnet(upstream: String): Int {
        val res = runRoot("""ip -o -4 addr show dev "$upstream" 2>/dev/null""")
        val upstreamInfo = res.output
        val chosen = CANDIDATE_OCTETS.firstOrNull { o ->
            !upstreamInfo.contains("192.168.$o.")
        } ?: 53
        activeOctet = chosen
        activeGatewayIp = "192.168.$chosen.1"
        activeSubnet = "192.168.$chosen.0/24"
        return chosen
    }

    fun findP2pInterface(): String? {
        val virt = runRoot("ip -o link | grep -oE 'p2p-[a-zA-Z0-9_-]+' | head -n 1")
        if (virt.exitCode == 0 && virt.output.isNotBlank()) return virt.output
        val direct = runRoot("ip -o link show dev p2p0 2>/dev/null | grep -E 'state UP|LOWER_UP' && echo p2p0")
        if (direct.exitCode == 0 && direct.output.contains("p2p0")) return "p2p0"
        return null
    }

    fun isInterfaceUp(iface: String): Boolean {
        val res = runRoot("""ip -o link show dev "$iface" 2>/dev/null | grep -E 'state UP|LOWER_UP|UP'""")
        return res.exitCode == 0 && res.output.isNotBlank()
    }

    /**
     * Root fallback for Android 9 where WifiP2pManager.requestGroupInfo() can return null.
     * Tries `dumpsys wifip2p` first (live active group), then p2p_supplicant.conf.
     */
    fun readActiveP2pCredentials(): Pair<String, String>? {
        val dump = runRoot("dumpsys wifip2p 2>/dev/null")
        if (dump.exitCode == 0 && dump.output.isNotBlank()) {
            val ssidMatch = Regex("""networkName:\s*(.+)""").findAll(dump.output).lastOrNull()
            val passMatch = Regex("""passphrase:\s*(.+)""").findAll(dump.output).lastOrNull()
            val s = ssidMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val p = passMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (s.isNotBlank() && s != "null" && s != "<none>") {
                return s to (if (p == "null" || p == "<none>") "" else p)
            }
        }
        val conf = runRoot(
            "cat /data/vendor/wifi/wpa/p2p_supplicant.conf /data/misc/wifi/p2p_supplicant.conf 2>/dev/null"
        )
        if (conf.exitCode == 0 && conf.output.isNotBlank()) {
            val ssidMatch = Regex("""ssid="([^"]+)"""").findAll(conf.output).lastOrNull()
            val pskMatch = Regex("""psk="([^"]+)"""").findAll(conf.output).lastOrNull()
            val s = ssidMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val p = pskMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (s.isNotBlank()) return s to p
        }
        return null
    }

    fun startRouting(iface: String, upstream: String, routeViaVpn: Boolean = false): ShellResult {
        val octet = selectNonCollidingSubnet(upstream)
        val gw = activeGatewayIp
        val sub = activeSubnet
        val rangeStart = "192.168.$octet.10"
        val rangeEnd = "192.168.$octet.250"
        val vpnIface = if (routeViaVpn) detectVpnInterface() else null
        activeVpnIface = vpnIface
        activeOutIface = vpnIface ?: upstream

        val d = '$'
        val vpnRules = if (vpnIface != null) {
            """
            ip route flush table 97 2>/dev/null || true
            ip route add default dev "$vpnIface" table 97
            ip rule add from $sub lookup 97 pref 95
            iptables -t nat -I POSTROUTING 1 -s $sub -o "$vpnIface" -j MASQUERADE
            iptables -C FORWARD -i "$iface" -o "$vpnIface" -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i "$iface" -o "$vpnIface" -j ACCEPT
            iptables -C FORWARD -i "$vpnIface" -o "$iface" -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i "$vpnIface" -o "$iface" -j ACCEPT
            """.trimIndent()
        } else {
            ""
        }

        val cmd = """
            ip addr flush dev "$iface" 2>/dev/null || true
            ip addr add $gw/24 dev "$iface"
            ip link set "$iface" up
            echo 1 > /proc/sys/net/ipv4/ip_forward
            ip rule del pref 90 2>/dev/null || true
            ip rule del pref 95 2>/dev/null || true
            ip rule del pref 100 2>/dev/null || true
            ip route flush table 97 2>/dev/null || true
            ip rule add to $sub lookup main pref 90
            ip rule add from $sub lookup "$upstream" pref 100
            for o in 49 50 51 52 53; do
                iptables -t nat -D POSTROUTING -s 192.168.${d}o.0/24 -o "$upstream" -j MASQUERADE 2>/dev/null || true
                for t in tun0 tun1 tun2; do
                    iptables -t nat -D POSTROUTING -s 192.168.${d}o.0/24 -o "${d}t" -j MASQUERADE 2>/dev/null || true
                done
            done
            iptables -t nat -I POSTROUTING 1 -s $sub -o "$upstream" -j MASQUERADE
            iptables -t nat -C PREROUTING -i "$iface" -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING 1 -i "$iface" -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53
            iptables -t nat -C PREROUTING -i "$iface" -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING 1 -i "$iface" -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53
            iptables -C FORWARD -i "$iface" -o "$upstream" -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i "$iface" -o "$upstream" -j ACCEPT
            iptables -C FORWARD -i "$upstream" -o "$iface" -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i "$upstream" -o "$iface" -j ACCEPT
            $vpnRules
            if [ -f "$PID_FILE" ]; then kill ${d}(cat "$PID_FILE") 2>/dev/null; rm -f "$PID_FILE"; fi
            pkill -f "dnsmasq.*$iface" 2>/dev/null || true
            touch "$LEASE_FILE"
            /system/bin/dnsmasq --interface="$iface" --bind-interfaces --dhcp-range=$rangeStart,$rangeEnd,12h --dhcp-option=3,$gw --dhcp-option=6,8.8.8.8,1.1.1.1 --port=0 --dhcp-leasefile="$LEASE_FILE" --pid-file="$PID_FILE" </dev/null >/dev/null 2>&1 || true
            echo "OK ($sub -> $activeOutIface)"
        """.trimIndent()
        return runRoot(cmd)
    }

    /** Returns null if intact, or a short reason tag if a rule is missing or VPN state changed. */
    fun checkRoutingHealth(iface: String, upstream: String, routeViaVpn: Boolean = false): String? {
        val currentVpn = if (routeViaVpn) detectVpnInterface() else null
        if (currentVpn != activeVpnIface) return "VPN_CHANGED"

        val gw = activeGatewayIp
        val sub = activeSubnet
        val d = '$'
        val vpnCheck = if (currentVpn != null) {
            """if ! ip rule list | grep -q "lookup 97"; then echo "NO_VPN_RULE"; exit 0; fi"""
        } else ""

        val cmd = """
            if [ "${d}(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)" != "1" ]; then echo "NO_FWD"; exit 0; fi
            if ! ip -4 addr show dev "$iface" 2>/dev/null | grep -q "$gw"; then echo "NO_IP"; exit 0; fi
            if ! ip rule list | grep -q "$sub"; then echo "NO_RULE"; exit 0; fi
            $vpnCheck
            if ! iptables -t nat -S PREROUTING 2>/dev/null | grep -q "8.8.8.8:53"; then echo "NO_DNS"; exit 0; fi
            if ! iptables -t nat -S POSTROUTING 2>/dev/null | grep -q "$sub"; then echo "NO_NAT"; exit 0; fi
            if ! iptables -S FORWARD 2>/dev/null | grep -q -- "-i $iface -o $upstream"; then echo "NO_IPT"; exit 0; fi
            echo "INTACT"
        """.trimIndent()
        val res = runRoot(cmd)
        if (res.output.contains("INTACT")) return null
        return res.output.ifBlank { "UNKNOWN" }
    }

    fun stopRouting(iface: String?, upstream: String): ShellResult {
        activeVpnIface = null
        activeOutIface = upstream
        val d = '$'
        val ifaceCleanup = if (!iface.isNullOrBlank()) {
            """
                iptables -D FORWARD -i "$iface" -o "$upstream" -j ACCEPT 2>/dev/null
                iptables -D FORWARD -i "$upstream" -o "$iface" -j ACCEPT 2>/dev/null
                for t in tun0 tun1 tun2; do
                    iptables -D FORWARD -i "$iface" -o "${d}t" -j ACCEPT 2>/dev/null || true
                    iptables -D FORWARD -i "${d}t" -o "$iface" -j ACCEPT 2>/dev/null || true
                done
                iptables -t nat -D PREROUTING -i "$iface" -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null
                iptables -t nat -D PREROUTING -i "$iface" -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null
                pkill -f "dnsmasq.*$iface" 2>/dev/null || true
            """.trimIndent()
        } else ""

        val cmd = """
            if [ -f "$PID_FILE" ]; then kill ${d}(cat "$PID_FILE") 2>/dev/null; rm -f "$PID_FILE"; fi
            ip rule del pref 90 2>/dev/null || true
            ip rule del pref 95 2>/dev/null || true
            ip rule del pref 100 2>/dev/null || true
            ip route flush table 97 2>/dev/null || true
            for o in 49 50 51 52 53; do
                iptables -t nat -D POSTROUTING -s 192.168.${d}o.0/24 -o "$upstream" -j MASQUERADE 2>/dev/null || true
                for t in tun0 tun1 tun2; do
                    iptables -t nat -D POSTROUTING -s 192.168.${d}o.0/24 -o "${d}t" -j MASQUERADE 2>/dev/null || true
                done
            done
            $ifaceCleanup
            echo "STOPPED"
        """.trimIndent()
        return runRoot(cmd)
    }

    fun countClients(iface: String): Int {
        val d = '$'
        val cmd = """ip -4 neigh show dev "$iface" 2>/dev/null | grep -E 'REACHABLE|DELAY|PROBE' | awk '{print ${d}5}' | sort -u | wc -l"""
        val res = runRoot(cmd)
        return res.output.trim().toIntOrNull() ?: 0
    }

    fun patchPmf(): ShellResult {
        val d = '$'
        val cmd = """
            FOUND=0
            for f in /data/vendor/wifi/wpa/p2p_supplicant.conf /data/misc/wifi/p2p_supplicant.conf; do
                if [ -f "${d}f" ]; then
                    FOUND=1
                    sed -i 's/^pmf=1/pmf=0/; s/^p2p_oper_reg_class=.*/p2p_oper_reg_class=81/; s/^p2p_oper_channel=.*/p2p_oper_channel=6/' "${d}f"
                    grep -q '^p2p_no_group_iface=' "${d}f" && sed -i 's/^p2p_no_group_iface=.*/p2p_no_group_iface=1/' "${d}f" || sed -i '2i p2p_no_group_iface=1' "${d}f"
                fi
            done
            if [ "${d}FOUND" = "1" ]; then echo "PATCHED"; else echo "NO_FILE"; fi
        """.trimIndent()
        return runRoot(cmd)
    }
}
