package fr.lanvitals;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class LanClient implements Runnable {
    interface Listener {
        void status(int message);
        Endpoint choose(List<Endpoint> servers) throws InterruptedException;
        boolean confirm(Endpoint server, String fingerprint) throws InterruptedException;
        void authenticated(String fingerprint, String password);
        void metrics(JSONObject values);
        void stopped(int message);
    }

    static final class Endpoint {
        final Network network;
        final InetAddress address;
        final int port;
        final String host;

        Endpoint(Network network, InetAddress address, int port, String host) {
            this.network = network;
            this.address = address;
            this.port = port;
            this.host = host;
        }

        @Override public String toString() {
            return host + "  ·  " + address.getHostAddress();
        }
    }

    private static final byte[] PROBE = "LAN-VITALS/1 DISCOVER".getBytes(StandardCharsets.US_ASCII);
    private final ConnectivityManager connectivity;
    private final Listener listener;
    private final String password;
    private String pin;
    private volatile boolean closed;
    private volatile Socket socket;
    private volatile Thread worker;

    LanClient(ConnectivityManager connectivity, Listener listener, String password, String pin) {
        this.connectivity = connectivity;
        this.listener = listener;
        this.password = password;
        this.pin = pin;
    }

    void close() {
        closed = true;
        Thread running = worker;
        if (running != null) running.interrupt();
        Socket active = socket;
        if (active != null) {
            try { active.close(); } catch (IOException ignored) {}
        }
    }

    @Override public void run() {
        worker = Thread.currentThread();
        try {
            boolean connected = false;
            while (!closed) {
                listener.status(connected ? R.string.reconnect : R.string.search);
                List<Endpoint> servers = discover();
                if (closed) return;
                if (servers.isEmpty()) {
                    listener.status(R.string.not_found);
                    Thread.sleep(1500);
                    continue;
                }
                if (pin == null && servers.size() > 1) {
                    Endpoint choice = listener.choose(servers);
                    if (choice == null) {
                        listener.stopped(R.string.cancelled_connection);
                        return;
                    }
                    servers = java.util.Collections.singletonList(choice);
                }
                boolean otherIdentity = false;
                for (Endpoint endpoint : servers) {
                    if (closed) return;
                    try {
                        stream(endpoint);
                    } catch (DifferentIdentity error) {
                        otherIdentity = true;
                    } catch (Stop error) {
                        listener.stopped(error.message);
                        return;
                    } catch (InterruptedException error) {
                        throw error;
                    } catch (Exception error) {
                        if (!closed) listener.status(R.string.interrupted);
                    }
                }
                connected = pin != null;
                if (otherIdentity && !closed) {
                    listener.status(R.string.different_identity);
                }
                Thread.sleep(1500);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            worker = null;
        }
    }

    private List<Endpoint> discover() {
        Map<String, Endpoint> servers = new LinkedHashMap<>();
        for (Network network : connectivity.getAllNetworks()) {
            if (closed || servers.size() >= 16) break;
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            LinkProperties links = connectivity.getLinkProperties(network);
            if (capabilities == null || links == null ||
                    !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                      || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            try (DatagramSocket probe = new DatagramSocket(null)) {
                probe.setBroadcast(true);
                network.bindSocket(probe);
                probe.bind(new InetSocketAddress(0));
                boolean sent = false;
                for (LinkAddress link : links.getLinkAddresses()) {
                    if (!(link.getAddress() instanceof Inet4Address)
                            || link.getPrefixLength() <= 0 || link.getPrefixLength() >= 31) continue;
                    int hostMask = -1 >>> link.getPrefixLength();
                    int broadcast = ipv4(link.getAddress()) | hostMask;
                    byte[] address = {
                        (byte) (broadcast >>> 24), (byte) (broadcast >>> 16),
                        (byte) (broadcast >>> 8), (byte) broadcast
                    };
                    probe.send(new DatagramPacket(PROBE, PROBE.length, InetAddress.getByAddress(address), 48321));
                    sent = true;
                }
                if (!sent) continue;
                long deadline = SystemClock.elapsedRealtime() + 850;
                byte[] buffer = new byte[1024];
                while (!closed && servers.size() < 16) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0) break;
                    probe.setSoTimeout((int) remaining);
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    probe.receive(reply);
                    if (reply.getPort() != 48321 || !onLink(reply.getAddress(), links)) continue;
                    try {
                        JSONObject data = new JSONObject(new String(buffer, 0, reply.getLength(), StandardCharsets.UTF_8));
                        int port = data.getInt("port");
                        if (!"LAN-VITALS/1".equals(data.getString("type")) || port < 1 || port > 65535) continue;
                        String host = data.getString("host");
                        if (host.length() > 255) continue;
                        String key = reply.getAddress().getHostAddress() + ":" + port;
                        if (!servers.containsKey(key)) {
                            servers.put(key, new Endpoint(network, reply.getAddress(), port, host));
                        }
                    } catch (org.json.JSONException ignored) {}
                }
            } catch (SocketTimeoutException ignored) {
            } catch (IOException ignored) {}
        }
        return new ArrayList<>(servers.values());
    }

    private static boolean onLink(InetAddress peer, LinkProperties links) {
        if (!(peer instanceof Inet4Address) || peer.isAnyLocalAddress()
                || peer.isLoopbackAddress() || peer.isMulticastAddress()) return false;
        for (LinkAddress link : links.getLinkAddresses()) {
            if (!(link.getAddress() instanceof Inet4Address) || link.getPrefixLength() <= 0) continue;
            int mask = -1 << (32 - link.getPrefixLength());
            if ((ipv4(link.getAddress()) & mask) == (ipv4(peer) & mask)) return true;
        }
        return false;
    }

    private static int ipv4(InetAddress address) {
        int value = 0;
        for (byte part : address.getAddress()) value = (value << 8) | (part & 0xff);
        return value;
    }

    private void stream(Endpoint endpoint) throws Exception {
        // L’empreinte enregistrée ou vérifiée explicitement protège le mot de passe.
        // Trust is established by the stored pin or explicit fingerprint verification
        // before any application data leaves this device.
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkServerTrusted(X509Certificate[] chain, String algorithm)
                    throws CertificateException {
                if (chain == null || chain.length != 1) throw new CertificateException("Invalid TLS identity");
                chain[0].checkValidity();
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String algorithm)
                    throws CertificateException { throw new CertificateException("Unexpected client certificate"); }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        try (Socket raw = endpoint.network.getSocketFactory().createSocket()) {
            socket = raw;
            if (closed) return;
            raw.connect(new InetSocketAddress(endpoint.address, endpoint.port), 2500);
            raw.setSoTimeout(4500);
            try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(
                    raw, endpoint.address.getHostAddress(), endpoint.port, true)) {
                tls.setEnabledProtocols(java.util.Arrays.asList(tls.getSupportedProtocols()).contains("TLSv1.3")
                        ? new String[]{"TLSv1.3", "TLSv1.2"} : new String[]{"TLSv1.2"});
                tls.startHandshake();
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(tls.getSession().getPeerCertificates()[0].getEncoded());
                StringBuilder hex = new StringBuilder();
                for (byte part : hash) {
                    if (hex.length() > 0) hex.append(':');
                    hex.append(String.format(Locale.ROOT, "%02X", part & 0xff));
                }
                String fingerprint = hex.toString();
                if (pin != null && !pin.equals(fingerprint)) throw new DifferentIdentity();
                if (pin == null && !listener.confirm(endpoint, fingerprint.substring(0, 35))) {
                    throw new Stop(R.string.cancelled_pairing);
                }
                if (closed) return;
                JSONObject request = new JSONObject().put("password", password).put("details", true);
                tls.getOutputStream().write((request + "\n").getBytes(StandardCharsets.UTF_8));
                tls.getOutputStream().flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(tls.getInputStream(), StandardCharsets.UTF_8));
                if (!"ok".equals(readMessage(reader).getString("type"))) throw new Stop(R.string.wrong_password);
                pin = fingerprint;
                listener.authenticated(pin, password);
                while (!closed) {
                    JSONObject values = readMessage(reader);
                    if ("metrics".equals(values.getString("type"))) listener.metrics(values);
                }
            }
        } finally {
            socket = null;
        }
    }

    private static JSONObject readMessage(BufferedReader reader) throws Exception {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 1_048_576; i++) {
            int character = reader.read();
            if (character == -1) throw new EOFException();
            if (character == '\n') return new JSONObject(message.toString());
            message.append((char) character);
        }
        throw new IOException("Message too long");
    }

    private static final class DifferentIdentity extends Exception {}
    private static final class Stop extends Exception {
        final int message;
        Stop(int message) { this.message = message; }
    }
}
