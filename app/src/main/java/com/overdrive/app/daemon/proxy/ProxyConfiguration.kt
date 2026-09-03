package com.overdrive.app.daemon.proxy

import com.overdrive.app.util.ScratchPaths

/**
 * Proxy configuration for VLESS Reality proxy.
 * 
 * Sensitive credentials are stored with AES-256-CBC encryption
 * using stack-based key reconstruction (SOTA Java obfuscation).
 * 
 * To add/update proxy credentials:
 * 1. Add values to secrets.json under "proxy" section
 * 2. Run: python generate_safe_enc.py
 * 3. Use Enc.PROXY_SERVER_IP etc. here
 * 
 * @see Safe
 * @see Enc
 */
object ProxyConfiguration {
    
    // Server configuration - add these to secrets.json and regenerate Enc.java
    // For now using placeholders - replace with Enc.PROXY_* after adding to secrets.json
    val SERVER_IP: String get() = Enc.PROXY_SERVER_IP
    val SERVER_PORT: Int get() = Enc.PROXY_SERVER_PORT.toIntOrNull() ?: 443
    val UUID: String get() = Enc.PROXY_UUID
    val SHORT_ID: String get() = Enc.PROXY_SHORT_ID
    val PUBLIC_KEY: String get() = Enc.PROXY_PUBLIC_KEY
    private val SNI: String get() = Enc.PROXY_SNI
    
    // Proxy configuration
    const val PROXY_PORT = 8119
    val PROXY_EXCLUSIONS: String get() = Enc.PROXY_EXCLUSIONS
    
    // Paths - use encrypted constants
    val CONFIG_PATH: String get() = ScratchPaths.path(Enc.SINGBOX_CONFIG)
    val LOG_PATH: String get() = ScratchPaths.path(Enc.SINGBOX_LOG)
    val SINGBOX_PATH: String get() = ScratchPaths.path(Enc.SINGBOX_BIN)
    
    /**
     * Default inbound bind address. Loopback keeps the proxy unreachable from
     * the network, which is the correct default for every existing caller.
     */
    val DEFAULT_LISTEN: String get() = Enc.LOCALHOST

    /**
     * Bind address that also accepts tethered hotspot clients. Only used when the
     * user opts into client proxying; clients must still set the proxy themselves
     * (tethered traffic cannot be redirected transparently).
     */
    const val ANY_LISTEN = "0.0.0.0"

    /**
     * Inbound port offered to tethered clients when they should be tunnelled.
     * Separate from [PROXY_PORT] so the car's own proxy keeps its behaviour: the
     * two inbounds live in ONE sing-box process and differ only in how the route
     * table sends them out.
     */
    const val CLIENT_TUNNEL_PORT = 8122

    private const val CLIENT_INBOUND_TAG = "clients-in"
    private const val CLIENT_PROXY_TAG = "clients-proxy"
    private const val CELL_RELAY_TAG = "cell-relay"

    /**
     * Generate sing-box configuration JSON for proxy mode.
     *
     * @param listen inbound bind address; defaults to loopback.
     * @param clientRelaySocksPort when non-null, add a second inbound on
     *   [CLIENT_TUNNEL_PORT] whose traffic is tunnelled and then handed to the
     *   cellular-bound SOCKS5 relay on this port.
     *
     * Why the extra hop is required: sing-box is an ordinary UID-2000 process, so
     * its sockets need a default network — and this device has none while the AP
     * owns the WiFi radio ("missing default interface"). Only an Android component
     * can bind a socket to the cellular network, so the relay must be the OUTERMOST
     * hop. The car's own `mixed-in` traffic keeps the plain outbound, so nothing
     * that depends on [PROXY_PORT] starts depending on the hotspot being up.
     */
    @JvmOverloads
    fun createConfig(
        listen: String = DEFAULT_LISTEN,
        clientRelaySocksPort: Int? = null,
    ): String {
        val listenAddress = if (listen.isBlank()) DEFAULT_LISTEN else listen
        val serverIp = SERVER_IP
        val serverPort = SERVER_PORT
        val uuid = UUID
        val publicKey = PUBLIC_KEY
        val shortId = SHORT_ID
        val sni = SNI
        val logPath = LOG_PATH

        // Second inbound + its detoured outbound + the route rule that ties them
        // together. All three are emitted only when client tunnelling is asked for,
        // so a normal config is byte-identical to before.
        val clientInbound = if (clientRelaySocksPort != null) """,
    {
      "type": "mixed",
      "tag": "$CLIENT_INBOUND_TAG",
      "listen": "$ANY_LISTEN",
      "listen_port": $CLIENT_TUNNEL_PORT,
      "sniff": true
    }""" else ""
        // NOTE: deliberately NO "domain_strategy" here. It would make sing-box resolve
        // the destination locally before dialing, and the only DNS server detours via
        // the plain `proxy` outbound — which has no route while the AP owns the radio.
        // Omitting it carries the hostname inside VLESS for the server to resolve.
        val clientOutbounds = if (clientRelaySocksPort != null) """,
    {
      "type": "${Enc.PROTO_VLESS}",
      "tag": "$CLIENT_PROXY_TAG",
      "server": "$SERVER_IP",
      "server_port": $SERVER_PORT,
      "uuid": "$UUID",
      "flow": "${Enc.FLOW_XTLS}",
      "connect_timeout": "5s",
      "detour": "$CELL_RELAY_TAG",
      "tls": {
        "enabled": true,
        "server_name": "${SNI}",
        "utls": { "enabled": true, "fingerprint": "${Enc.FINGERPRINT_CHROME}" },
        "reality": { "enabled": true, "public_key": "${PUBLIC_KEY}", "short_id": "${SHORT_ID}" }
      }
    },
    {
      "type": "socks",
      "tag": "$CELL_RELAY_TAG",
      "server": "127.0.0.1",
      "server_port": $clientRelaySocksPort,
      "version": "5"
    }""" else ""
        val clientRule = if (clientRelaySocksPort != null) """,
      { "inbound": ["$CLIENT_INBOUND_TAG"], "outbound": "$CLIENT_PROXY_TAG" }""" else ""

        return """
{
  "log": { "level": "error", "timestamp": true, "output": "$logPath" },
  "dns": {
    "servers": [
      { "tag": "google", "address": "8.8.8.8", "detour": "proxy" }
    ],
    "final": "google",
    "strategy": "ipv4_only"
  },
  "inbounds": [
    {
      "type": "mixed",
      "tag": "mixed-in",
      "listen": "$listenAddress",
      "listen_port": $PROXY_PORT,
      "sniff": true
    }$clientInbound
  ],
  "outbounds": [
    {
      "type": "${Enc.PROTO_VLESS}",
      "tag": "${Enc.OUTBOUND_PROXY}",
      "server": "$serverIp",
      "server_port": $serverPort,
      "uuid": "$uuid",
      "flow": "${Enc.FLOW_XTLS}",
      "domain_strategy": "ipv4_only",
      "tcp_fast_open": false,
      "connect_timeout": "3s",
      "handshake_timeout": "3s",
      "multiplex": {
        "enabled": true,
        "protocol": "h2mux",
        "max_connections": 1, 
        "min_streams": 2,
        "padding": true
      },
      "tls": {
        "enabled": true,
        "server_name": "$sni",
        "utls": { "enabled": true, "fingerprint": "${Enc.FINGERPRINT_CHROME}" },
        "reality": {
          "enabled": true,
          "public_key": "$publicKey",
          "short_id": "$shortId"
        }
      }
    },
    { "type": "${Enc.OUTBOUND_DIRECT}", "tag": "${Enc.OUTBOUND_DIRECT}" }$clientOutbounds
  ],
  "route": {
    "auto_detect_interface": true,
    "override_android_vpn": true,
    "rules": [
      { "inbound": ["mixed-in"], "protocol": "dns", "outbound": "${Enc.OUTBOUND_PROXY}" }$clientRule
    ]
  }
}
    """.trimIndent()
    }
}
