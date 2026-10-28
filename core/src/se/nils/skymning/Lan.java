package se.nils.skymning;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Small, bounded, server-authoritative LAN transport. It never writes on the UI thread. */
public final class Lan implements AutoCloseable {
    public static final int PORT = 28741;
    public static final int CONNECTED = 1, DISCONNECTED = 2, DATA = 3, ERROR = 4;
    public static final int MAX_FRAME = 65536;
    private static final int MAGIC = 0x534B594D, VERSION = 1;
    public final boolean hosting;
    public volatile int localId;
    public final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Connection> connections = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger handshakes = new AtomicInteger();
    private ServerSocket listener;

    public static final class Event {
        public final int type, peer;
        public final byte[] data;
        Event(int type, int peer, byte[] data) {
            this.type = type;
            this.peer = peer;
            this.data = data;
        }
        public String message() { return new String(data, StandardCharsets.UTF_8); }
    }

    private Lan(boolean hosting) { this.hosting = hosting; localId = hosting ? 0 : -1; }

    public static Lan host() throws IOException { return host(PORT); }

    /** Port 0 selects an unused port, useful for isolated regression tests. */
    public static Lan host(int port) throws IOException {
        Lan lan = new Lan(true);
        lan.listener = new ServerSocket();
        try {
            lan.listener.setReuseAddress(true);
            lan.listener.bind(new InetSocketAddress(port));
            thread("skymning-accept", lan::acceptLoop);
            return lan;
        } catch (IOException e) {
            lan.close();
            throw new IOException("Kunde inte öppna rummet. Ett annat rum kanske redan körs.", e);
        }
    }

    public int port() { return listener == null ? PORT : listener.getLocalPort(); }
    public static Lan join(String address) throws IOException { return join(address, PORT); }

    public static Lan join(String address, int port) throws IOException {
        if (address == null || address.trim().isEmpty() || address.length() > 253)
            throw new IOException("Skriv värdens lokala IP-adress.");
        // Numeric IPv4 prevents an unbounded DNS lookup and keeps joining local and predictable.
        String host = address.trim();
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) throw new IOException("Ange en IPv4-adress, till exempel 192.168.1.20.");
        for (String octet : octets) {
            try {
                if (octet.isEmpty() || octet.length() > 3 || !octet.matches("[0-9]+") || Integer.parseInt(octet) > 255)
                    throw new NumberFormatException();
            } catch (NumberFormatException e) { throw new IOException("IP-adressen är inte giltig."); }
        }
        Lan lan = new Lan(false);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 4000);
            socket.setSoTimeout(4000);
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(MAGIC); out.writeInt(VERSION); out.flush();
            if (in.readInt() != MAGIC || in.readInt() != VERSION)
                throw new IOException("Spelets version skiljer sig från värdens version.");
            int id = in.readInt();
            if (id == -1) throw new IOException("Rummet är fullt. Högst sex spelare kan delta.");
            if (id < 1 || id > 5) throw new IOException("Värden skickade ett ogiltigt spelar-ID.");
            lan.localId = id;
            Connection connection = lan.new Connection(0, socket, in, out);
            synchronized (lan.connections) { lan.connections.put(0, connection); }
            lan.events.add(new Event(CONNECTED, 0, new byte[0]));
            connection.start();
            return lan;
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) { }
            lan.close();
            String detail=e.getMessage();
            if(detail!=null&&(detail.startsWith("Rummet är fullt")||detail.startsWith("Spelets version")))throw new IOException(detail,e);
            throw new IOException("Kunde inte ansluta. Kontrollera IP-adressen, att rummet är öppet och att ni använder samma Wi-Fi.", e);
        }
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = listener.accept();
                if (handshakes.incrementAndGet() > 8) {
                    handshakes.decrementAndGet(); socket.close(); continue;
                }
                thread("skymning-handshake", () -> accept(socket));
            } catch (IOException e) {
                if (!closed.get()) { event(ERROR, -1, "Rummet kunde inte ta emot fler anslutningar."); close(); }
            }
        }
    }

    private void accept(Socket socket) {
        boolean retained = false;
        try {
            socket.setSoTimeout(3000);
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return;
            Connection connection;
            synchronized (connections) {
                if (closed.get()) return;
                int id = 1;
                while (id <= 5 && connections.containsKey(id)) id++;
                if (id > 5) {
                    out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(-1); out.flush();
                    return;
                }
                connection = new Connection(id, socket, in, out);
                connections.put(id, connection);
            }
            try {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(connection.id); out.flush();
                if (closed.get()) { connection.stop(null); return; }
                events.add(new Event(CONNECTED, connection.id, new byte[0]));
                connection.start();
                retained = true;
            } catch (IOException e) { connection.stop(null); }
        } catch (IOException ignored) {
            // An incomplete or incompatible handshake is not a game-level disconnect.
        } finally {
            handshakes.decrementAndGet();
            if (!retained) try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** Remote peer IDs only: host returns 1..5, guest returns 0. */
    public int[] peers() {
        synchronized (connections) {
            int[] result = new int[connections.size()]; int i = 0;
            for (int id : connections.keySet()) result[i++] = id;
            Arrays.sort(result); return result;
        }
    }

    public void send(byte[] bytes) {
        validate(bytes);
        Connection[] copy;
        synchronized (connections) { copy = connections.values().toArray(new Connection[0]); }
        for (Connection connection : copy) connection.enqueue(bytes);
    }

    public void sendTo(int peer, byte[] bytes) {
        validate(bytes);
        Connection connection;
        synchronized (connections) { connection = connections.get(peer); }
        if (connection != null) connection.enqueue(bytes);
    }

    private static void validate(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_FRAME)
            throw new IllegalArgumentException("Nätverksmeddelanden måste vara 1–65536 byte.");
    }

    private void event(int type, int id, String message) {
        events.add(new Event(type, id, message.getBytes(StandardCharsets.UTF_8)));
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        Connection[] copy;
        synchronized (connections) { copy = connections.values().toArray(new Connection[0]); }
        for (Connection connection : copy) connection.stop(null);
    }

    private final class Connection {
        final int id;
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        final ArrayBlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<>(32);
        final AtomicBoolean stopped = new AtomicBoolean();
        Thread writer;
        Connection(int id, Socket socket, DataInputStream in, DataOutputStream out) {
            this.id = id; this.socket = socket; this.in = in; this.out = out;
        }
        void start() throws IOException {
            socket.setSoTimeout(12000);
            writer = thread("skymning-send-" + id, this::writeLoop);
            thread("skymning-read-" + id, this::readLoop);
        }
        void enqueue(byte[] bytes) {
            if (stopped.get()) return;
            if (!outgoing.offer(bytes.clone())) stop("Anslutningen är för långsam. Anslut till rummet igen.");
        }
        void writeLoop() {
            try {
                while (!stopped.get()) {
                    byte[] data = outgoing.poll(2, TimeUnit.SECONDS);
                    out.writeInt(data == null ? -1 : data.length);
                    if (data != null) out.write(data);
                    out.flush();
                }
            } catch (IOException e) { stop("Anslutningen bröts."); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        void readLoop() {
            long window = System.nanoTime(); int messages = 0;
            try {
                while (!stopped.get()) {
                    int size = in.readInt();
                    long now = System.nanoTime();
                    if (now - window > 1_000_000_000L) { window = now; messages = 0; }
                    if (++messages > 180) throw new IOException("För många meddelanden.");
                    if (size == -1) continue;
                    if (size < 1 || size > MAX_FRAME) throw new IOException("Ogiltigt meddelande.");
                    byte[] data = new byte[size]; in.readFully(data);
                    if (events.size() >= 256) throw new IOException("Meddelandekön är full.");
                    events.add(new Event(DATA, id, data));
                }
            } catch (IOException e) { stop("Anslutningen bröts. Återvänd till menyn för att ansluta igen."); }
        }
        void stop(String reason) {
            if (!stopped.compareAndSet(false, true)) return;
            try { socket.close(); } catch (IOException ignored) { }
            if (writer != null) writer.interrupt();
            outgoing.clear();
            synchronized (connections) {
                if (connections.get(id) == this) connections.remove(id);
                // Publish before another handshake can reuse this player ID.
                if (!closed.get()) event(DISCONNECTED, id, reason == null ? "Spelaren lämnade rummet." : reason);
            }
        }
    }

    private static Thread thread(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name); thread.setDaemon(true); thread.start(); return thread;
    }
}
