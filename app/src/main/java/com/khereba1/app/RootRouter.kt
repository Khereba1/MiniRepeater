package com.khereba1.app

import java.io.BufferedReader
import java.io.InputStreamReader

data class ShellResult(val exitCode: Int, val output: String)

object RootRouter {
    const val GATEWAY_IP = "192.168.49.1"
    const val SUBNET = "192.168.49.0/24"
    const val LEASE_FILE = "/data/local/tmp/minirepeater_dhcp.leases"
    const val PID_FILE = "/data/local/tmp/minirepeater_dnsmasq.pid"

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
        return if (res.exitCode == 0 && res.output.isNotBlank()) res.output else "wlan0"
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

    fun startRouting(iface: String, upstream: String): ShellResult {
        val d = '$'
        val cmd = """
            ip addr replace $GATEWAY_IP/24 dev "$iface"
            ip link set "$iface" up
            echo 1 > /proc/sys/net/ipv4/ip_forward
            ip rule del pref 90 2>/dev/null
            ip rule del pref 100 2>/dev/null
            ip rule add to $SUBNET lookup main pref 90
            ip rule add from $SUBNET lookup "$upstream" pref 100
            iptables -t nat -C POSTROUTING -s $SUBNET -o "$upstream" -j MASQUERADE 2>/dev/null || iptables -t nat -I POSTROUTING 1 -s $SUBNET -o "$upstream" -j MASQUERADE
            iptables -t nat -C PREROUTING -i "$iface" -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING 1 -i "$iface" -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53
            iptables -t nat -C PREROUTING -i "$iface" -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null || iptables -t nat -I PREROUTING 1 -i "$iface" -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53
            iptables -C FORWARD -i "$iface" -o "$upstream" -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i "$iface" -o "$upstream" -j ACCEPT
            iptables -C FORWARD -i "$upstream" -o "$iface" -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i "$upstream" -o "$iface" -j ACCEPT
            if [ -f "$PID_FILE" ]; then kill ${d}(cat "$PID_FILE") 2>/dev/null; rm -f "$PID_FILE"; fi
            if ! pgrep dnsmasq >/dev/null 2>&1; then
                touch "$LEASE_FILE"
                /system/bin/dnsmasq --interface="$iface" --bind-interfaces --dhcp-range=192.168.49.10,192.168.49.250,12h --dhcp-option=3,$GATEWAY_IP --dhcp-option=6,8.8.8.8,1.1.1.1 --port=0 --dhcp-leasefile="$LEASE_FILE" --pid-file="$PID_FILE" </dev/null >/dev/null 2>&1 || true
            fi
            echo "OK"
        """.trimIndent()
        return runRoot(cmd)
    }

    /** Returns null if intact, or a short reason tag if a rule is missing. */
    fun checkRoutingHealth(iface: String, upstream: String): String? {
        val d = '$'
        val cmd = """
            if [ "${d}(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)" != "1" ]; then echo "NO_FWD"; exit 0; fi
            if ! ip rule list | grep -q "$SUBNET"; then echo "NO_RULE"; exit 0; fi
            if ! iptables -t nat -S PREROUTING 2>/dev/null | grep -q "8.8.8.8:53"; then echo "NO_DNS"; exit 0; fi
            if ! iptables -t nat -S POSTROUTING 2>/dev/null | grep -q "$SUBNET"; then echo "NO_NAT"; exit 0; fi
            if ! iptables -S FORWARD 2>/dev/null | grep -q -- "-i $iface -o $upstream"; then echo "NO_IPT"; exit 0; fi
            echo "INTACT"
        """.trimIndent()
        val res = runRoot(cmd)
        if (res.output.contains("INTACT")) return null
        return res.output.ifBlank { "UNKNOWN" }
    }

    fun stopRouting(iface: String?, upstream: String): ShellResult {
        val d = '$'
        val ifaceCleanup = if (!iface.isNullOrBlank()) {
            """
                iptables -D FORWARD -i "$iface" -o "$upstream" -j ACCEPT 2>/dev/null
                iptables -D FORWARD -i "$upstream" -o "$iface" -j ACCEPT 2>/dev/null
                iptables -t nat -D PREROUTING -i "$iface" -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null
                iptables -t nat -D PREROUTING -i "$iface" -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null
            """.trimIndent()
        } else ""

        val cmd = """
            if [ -f "$PID_FILE" ]; then kill ${d}(cat "$PID_FILE") 2>/dev/null; rm -f "$PID_FILE"; fi
            ip rule del pref 90 2>/dev/null
            ip rule del pref 100 2>/dev/null
            iptables -t nat -D POSTROUTING -s $SUBNET -o "$upstream" -j MASQUERADE 2>/dev/null
            $ifaceCleanup
            echo "STOPPED"
        """.trimIndent()
        return runRoot(cmd)
    }

    fun countClients(iface: String): Int {
        val cmd = """ip neigh show dev "$iface" 2>/dev/null | grep -E 'REACHABLE|STALE|DELAY' | wc -l"""
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
