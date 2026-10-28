package se.nils.skymning;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Real socket regression checks; run with java -ea se.nils.skymning.LanTest. */
public final class LanTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static void await(BooleanSupplier condition, String label) throws Exception {
        long end = System.nanoTime() + 4_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        check(condition.getAsBoolean(), label);
    }
    private static Lan.Event event(Lan lan, int type) throws Exception {
        long end = System.nanoTime() + 4_000_000_000L;
        while (System.nanoTime() < end) {
            Lan.Event event = lan.events.poll();
            if (event != null && event.type == type) return event;
            Thread.sleep(2);
        }
        throw new AssertionError("Missing event " + type);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        try (Lan host = Lan.host(0); Lan a = Lan.join("127.0.0.1", host.port()); Lan b = Lan.join("127.0.0.1", host.port())) {
            check(host.hosting && !a.hosting, "roles");
            check(a.localId == 1 && b.localId == 2, "unique IDs");
            check(Arrays.equals(host.peers(), new int[]{1, 2}), "host peers");
            check(Arrays.equals(a.peers(), new int[]{0}), "guest peer");
            check(event(host, Lan.CONNECTED).peer == 1, "first connect event");
            check(event(host, Lan.CONNECTED).peer == 2, "second connect event");
            a.send(bytes("input"));
            Lan.Event input = event(host, Lan.DATA);
            check(input.peer == 1 && input.message().equals("input"), "client input authenticated to peer");
            host.send(bytes("snapshot"));
            check(event(a, Lan.DATA).message().equals("snapshot"), "broadcast a");
            check(event(b, Lan.DATA).message().equals("snapshot"), "broadcast b");
            host.sendTo(2, bytes("private"));
            check(event(b, Lan.DATA).message().equals("private"), "targeted send");
            Thread.sleep(50);
            check(a.events.isEmpty(), "target isolation");
            byte[] original = bytes("immutable"); a.send(original); original[0] = 'X';
            check(event(host, Lan.DATA).message().equals("immutable"), "caller mutation isolated");
            boolean rejected = false;
            try { a.send(new byte[Lan.MAX_FRAME + 1]); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "oversized outgoing rejected");
            b.close();
            check(event(host, Lan.DISCONNECTED).peer == 2, "disconnect notification");
            await(() -> host.peers().length == 1, "disconnect frees slot");
            try (Lan replacement = Lan.join("127.0.0.1", host.port())) {
                check(replacement.localId == 2, "slot reused");
            }
        }
        try (Lan host = Lan.host(0)) {
            Lan[] guests = new Lan[5];
            try {
                for (int i = 0; i < 5; i++) guests[i] = Lan.join("127.0.0.1", host.port());
                boolean rejected = false;
                try (Lan ignored = Lan.join("127.0.0.1", host.port())) { }
                catch (IOException expected) { rejected = expected.getMessage().contains("fullt"); }
                check(rejected, "six player room capacity");
            } finally { for (Lan guest : guests) if (guest != null) guest.close(); }
        }
        try (Lan host = Lan.host(0); Socket malicious = new Socket("127.0.0.1", host.port())) {
            malicious.setSoTimeout(4000);
            DataOutputStream out = new DataOutputStream(malicious.getOutputStream());
            DataInputStream in = new DataInputStream(malicious.getInputStream());
            out.writeInt(0x534B594D); out.writeInt(1); out.flush();
            in.readInt(); in.readInt(); int id = in.readInt();
            event(host, Lan.CONNECTED);
            out.writeInt(Integer.MAX_VALUE); out.flush();
            check(event(host, Lan.DISCONNECTED).peer == id, "invalid frame disconnects safely");
            check(in.read() == -1, "invalid socket closed");
        }
        try (Lan host = Lan.host(0); Socket incompatible = new Socket("127.0.0.1", host.port())) {
            incompatible.setSoTimeout(4000);
            DataOutputStream out = new DataOutputStream(incompatible.getOutputStream());
            out.writeInt(0x534B594D); out.writeInt(999); out.flush();
            check(incompatible.getInputStream().read() == -1, "protocol mismatch rejected");
            check(host.peers().length == 0, "mismatch consumes no slot");
        }
        try (Lan host = Lan.host(0); Lan client = Lan.join("127.0.0.1", host.port())) {
            event(host, Lan.CONNECTED); event(client, Lan.CONNECTED);
            Thread.sleep(12500);
            check(host.peers().length == 1 && client.peers().length == 1, "heartbeats preserve idle lobby past socket timeout");
            check(host.events.isEmpty() && client.events.isEmpty(), "heartbeats invisible to gameplay");
            client.send(bytes("after idle"));
            check(event(host, Lan.DATA).message().equals("after idle"), "traffic resumes after idle");
            host.close();
            check(event(client, Lan.DISCONNECTED).peer == 0, "host shutdown reaches client");
        }
        boolean invalid = false;
        try { Lan.join("not-an-address"); } catch (IOException expected) { invalid = true; }
        check(invalid, "invalid address rejected without DNS");
        System.out.println("LanTest: " + checks + " checks passed");
    }
}
