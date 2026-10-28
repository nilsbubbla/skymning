package se.nils.skymning;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;

/**
 * External peer for physical-device integration tests. No device controls or game cheats.
 * Android host: adb forward tcp:28741 tcp:28741, then DevicePeer --join 127.0.0.1 --rematch.
 * Android guest: adb reverse tcp:28741 tcp:28741, then DevicePeer --host --speed 6;
 * join 127.0.0.1 on the phone. Speed affects this standalone test host only.
 */
public final class DevicePeer {
    private static void log(String text) { System.out.println(Instant.now() + " " + text); System.out.flush(); }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static byte[] state(Game game) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(1); out.writeByte(game.map); out.write(game.snapshot()); return bytes.toByteArray();
    }
    private static byte[] input(float x, float y, boolean ability) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(2); out.writeFloat(x); out.writeFloat(y); out.writeBoolean(ability); return bytes.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        boolean host = false, rematch = false;
        String address = "127.0.0.1";
        int port = Lan.PORT, rounds = 2, map = 0, timeout = 320;
        float speed = 1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = true; break;
                case "--join": address = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--rounds": rounds = Integer.parseInt(args[++i]); break;
                case "--map": map = Integer.parseInt(args[++i]); break;
                case "--timeout": timeout = Integer.parseInt(args[++i]); break;
                case "--speed": speed = Float.parseFloat(args[++i]); break;
                case "--rematch": rematch = true; break;
                default: throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        require(timeout > 0 && timeout <= 900, "timeout 1..900 seconds");
        require(speed > 0 && speed <= 20 && Float.isFinite(speed), "speed 0..20");
        if (host) host(port, map, rounds, speed, timeout);
        else join(address, port, rematch, timeout);
    }

    private static void join(String address, int port, boolean expectRematch, int timeout) throws Exception {
        long deadline = System.nanoTime() + timeout * 1_000_000_000L;
        try (Lan lan = Lan.join(address, port)) {
            log("JOINED player=" + lan.localId + "; waiting for Android host to start");
            Game game = new Game(0, 1);
            int priorPhase = -1, packets = 0, inputs = 0, results = 0;
            boolean lobby = false, hide = false, seek = false, moved = false, boost = false;
            float initialX = 0, initialY = 0, previousTime = 100;
            long start = System.nanoTime(), lastInput = 0, lastStatus = 0, resultAt = 0;
            while (System.nanoTime() < deadline) {
                Lan.Event event;
                while ((event = lan.events.poll()) != null) {
                    if (event.type == Lan.DISCONNECTED || event.type == Lan.ERROR)
                        throw new IOException("Unexpected disconnect: " + event.message());
                    if (event.type != Lan.DATA) continue;
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.data));
                    int type = in.readUnsignedByte();
                    if (type == 3) {
                        int map = in.readUnsignedByte(); require(map == 0 || map == 1, "valid lobby map");
                        if (!lobby) { log("LOBBY map=" + map); lobby = true; }
                    } else if (type == 1) {
                        int map = in.readUnsignedByte(); game.restore(Arrays.copyOfRange(event.data, 2, event.data.length));
                        require(map == game.map, "outer and inner map agree");
                        require(game.players[lan.localId].human, "host assigned human guest");
                        packets++;
                        Game.Player me = game.players[lan.localId];
                        if (packets == 1) { initialX = me.x; initialY = me.y; }
                        if (Math.hypot(me.x - initialX, me.y - initialY) > 25) moved = true;
                        if (me.boost > 0 || me.cooldown > 0) boost = true;
                        if (game.phase == priorPhase && game.phase != 2)
                            require(game.time <= previousTime + .02f, "timer never reverses within phase");
                        if (game.phase != priorPhase) {
                            log("PHASE " + priorPhase + " -> " + game.phase + " remaining=" + game.time + " packets=" + packets);
                            if (game.phase == 0) {
                                if (priorPhase == 2) {
                                    require(hide && seek && results > 0, "complete first round before rematch");
                                    require(moved && boost, "movement and ability reached authoritative host");
                                    log("PASS rematch synchronized; lobby=" + lobby + " snapshots=" + packets + " inputs=" + inputs + " movement=" + moved + " ability=" + boost);
                                    return;
                                }
                                hide = true;
                            }
                            if (game.phase == 1) { require(hide, "hide observed before seeking"); seek = true; }
                            if (game.phase == 2) { require(hide && seek, "full round observed"); results++; resultAt = System.nanoTime(); }
                            priorPhase = game.phase;
                        }
                        previousTime = game.time;
                    } else throw new IOException("Unexpected game packet " + type);
                }
                long now = System.nanoTime();
                if (packets > 0 && now - lastInput >= 80_000_000L) {
                    // A repeatable square around spawn exercises movement and one-shot ability.
                    int direction = (int) ((now - start) / 1_500_000_000L) % 4;
                    float x = direction == 0 ? 1 : direction == 2 ? -1 : 0;
                    float y = direction == 1 ? 1 : direction == 3 ? -1 : 0;
                    lan.send(input(x, y, inputs % 35 == 0)); inputs++; lastInput = now;
                }
                if (now - lastStatus > 10_000_000_000L) {
                    log("STATUS phase=" + priorPhase + " time=" + game.time + " snapshots=" + packets + " inputs=" + inputs);
                    lastStatus = now;
                }
                if (!expectRematch && results > 0 && now - resultAt > 2_000_000_000L) {
                    require(moved && boost, "movement and ability acknowledged");
                    log("PASS full round; lobby=" + lobby + " snapshots=" + packets + " inputs=" + inputs + " movement=" + moved + " ability=" + boost);
                    return;
                }
                Thread.sleep(4);
            }
            throw new AssertionError("Timed out waiting for " + (expectRematch ? "full round and rematch" : "full round"));
        }
    }

    private static void host(int port, int map, int rounds, float speed, int timeout) throws Exception {
        require(rounds > 0 && rounds <= 10, "round count 1..10");
        long deadline = System.nanoTime() + timeout * 1_000_000_000L;
        try (Lan lan = Lan.host(port)) {
            log("HOST_READY port=" + lan.port() + " map=" + map + " speed=" + speed);
            Game game = null;
            long connectedAt = 0, resultAt = 0, previous = System.nanoTime(), lastSend = 0;
            int round = 0, inputCount = 0, movementInputs = 0, abilityInputs = 0, previousPhase = -1;
            double accumulator = 0;
            while (System.nanoTime() < deadline) {
                long now = System.nanoTime();
                double dt = Math.min(.1, (now - previous) / 1e9); previous = now;
                Lan.Event event;
                while ((event = lan.events.poll()) != null) {
                    if (event.type == Lan.CONNECTED) {
                        log("ANDROID_CONNECTED id=" + event.peer);
                        if (connectedAt == 0) connectedAt = now;
                        if (game != null) game.players[event.peer].human = true;
                    } else if (event.type == Lan.DISCONNECTED) {
                        log("ANDROID_DISCONNECTED id=" + event.peer);
                        if (game != null) game.players[event.peer].human = false;
                    } else if (event.type == Lan.DATA) {
                        require(event.data.length == 10 && event.data[0] == 2, "valid Android input packet");
                        DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.data)); in.readByte();
                        float x = in.readFloat(), y = in.readFloat(); boolean ability = in.readBoolean();
                        require(Float.isFinite(x) && Float.isFinite(y) && Math.abs(x) <= 1 && Math.abs(y) <= 1, "bounded input");
                        if (game != null) game.input(event.peer, x, y, ability);
                        inputCount++;
                        if (x != 0 || y != 0) movementInputs++;
                        if (ability) abilityInputs++;
                    }
                }
                if (game == null && connectedAt > 0 && now - connectedAt >= 8_000_000_000L) {
                    game = new Game(map, 17); round = 1; previousPhase = -1;
                    for (int peer : lan.peers()) game.players[peer].human = true;
                    log("ROUND_STARTED round=" + round);
                }
                if (game != null) {
                    if (game.phase != 2) {
                        accumulator += dt * speed;
                        while (accumulator >= 1.0 / 60) { game.update(1f / 60f); accumulator -= 1.0 / 60; }
                    }
                    if (game.phase != previousPhase) {
                        log("PHASE round=" + round + " phase=" + game.phase + " inputs=" + inputCount);
                        if (game.phase == 2) resultAt = now;
                        previousPhase = game.phase;
                    }
                    if (game.phase == 2 && now - resultAt > 7_000_000_000L) {
                        if (round >= rounds) {
                            require(inputCount > 10, "Android sent continuous control input");
                            log("PASS hosted " + rounds + " rounds; peer inputs=" + inputCount + "; movement=" + movementInputs + "; ability=" + abilityInputs + "; closing room");
                            return;
                        }
                        game = new Game(map, 17 + round); round++; previousPhase = -1; accumulator = 0;
                        for (int peer : lan.peers()) game.players[peer].human = true;
                        log("REMATCH_STARTED round=" + round);
                    }
                }
                if (now - lastSend >= 75_000_000L) {
                    lan.send(game == null ? new byte[]{3, (byte) map} : state(game)); lastSend = now;
                }
                Thread.sleep(4);
            }
            throw new AssertionError("Host test timed out");
        }
    }
}
