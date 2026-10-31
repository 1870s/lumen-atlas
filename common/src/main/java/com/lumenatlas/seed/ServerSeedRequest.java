package com.lumenatlas.seed;

import com.lumenatlas.LumenAtlas;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Asks the server for its seed with the vanilla {@code /seed} command and reads the answer from the incoming system
 * message. This only works where the server allows the player to use {@code /seed} (operators on servers and Realms,
 * cheats enabled on LAN worlds). The client knows that in advance: servers only send the commands a player may use,
 * so {@link #isAvailable} simply checks the command tree.
 */
public final class ServerSeedRequest {
    public enum State { IDLE, WAITING, RECEIVED, FAILED }

    private static final long TIMEOUT_MS = 5000;
    /** Fallback for servers that send the answer as plain (already translated) English text. */
    private static final Pattern PLAIN_ANSWER = Pattern.compile("^Seed: \\[(-?\\d+)]$");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+");

    private static State state = State.IDLE;
    private static long deadline;
    private static long received;
    private static LongConsumer callback;

    private ServerSeedRequest() {}

    /** True when the server lets this player use /seed. */
    public static boolean isAvailable() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        return mc.player != null && connection != null && connection.getCommands().getRoot().getChild("seed") != null;
    }

    public static State state() {
        return state;
    }

    public static long receivedSeed() {
        return received;
    }

    /** Sends /seed; {@code onSeed} is called on the client thread when the answer arrives. */
    public static boolean start(LongConsumer onSeed) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || !isAvailable()) {
            state = State.FAILED;
            return false;
        }
        callback = onSeed;
        state = State.WAITING;
        deadline = System.currentTimeMillis() + TIMEOUT_MS;
        connection.sendCommand("seed");
        return true;
    }

    /** Called every client tick. */
    public static void tick() {
        if (state == State.WAITING && System.currentTimeMillis() > deadline) {
            state = State.FAILED;
            callback = null;
            LumenAtlas.LOGGER.info("Lumen Atlas: the server did not answer /seed");
        }
    }

    /** Called for every system message the client receives. */
    public static void onSystemMessage(Component message) {
        if (state != State.WAITING || message == null) return;
        Long seed = parse(message);
        if (seed == null) return;
        received = seed;
        state = State.RECEIVED;
        LongConsumer done = callback;
        callback = null;
        if (done != null) done.accept(seed);
    }

    /** The seed in a /seed answer, or null when the message is something else. */
    static Long parse(Component message) {
        if (message.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals("commands.seed.success")) {
            Object[] args = translatable.getArgs();
            if (args.length > 0) {
                String text = args[0] instanceof Component c ? c.getString() : String.valueOf(args[0]);
                Matcher m = NUMBER.matcher(text);
                if (m.find()) return parseLong(m.group());
            }
            return null;
        }
        Matcher plain = PLAIN_ANSWER.matcher(message.getString().trim());
        return plain.matches() ? parseLong(plain.group(1)) : null;
    }

    private static Long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
