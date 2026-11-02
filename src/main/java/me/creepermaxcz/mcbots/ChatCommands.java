package me.creepermaxcz.mcbots;

import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-game commands, typed in the chat by an admin and read by the bot holding the {@link MainListener}:
 * <pre>
 * !bots bedrock &lt;count&gt;          connect Bedrock bots through Geyser
 * !bots java &lt;count&gt;             connect Java bots
 * !bots leave [all|java|bedrock] [count]
 * !bots list
 * !bots help
 * </pre>
 * The sender is identified by the UUID of the player chat message, looked up in the player list,
 * so a player cannot pretend to be an admin by typing an admin's name in a message.
 */
public final class ChatCommands {

    public static final String PREFIX = "!bots";

    /** Upper limit for one command, to avoid typos like "!bots bedrock 1000" */
    private static final int MAX_PER_COMMAND = 200;

    private static final Set<String> admins = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, String> playerNames = new ConcurrentHashMap<>();

    private ChatCommands() {
    }

    public static void setAdmins(String commaSeparated) {
        for (String admin : commaSeparated.split(",")) {
            String trimmed = admin.trim();
            if (!trimmed.isEmpty()) {
                admins.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
    }

    public static boolean isEnabled() {
        return !admins.isEmpty();
    }

    public static Set<String> getAdmins() {
        return admins;
    }

    public static void onPlayerInfo(PlayerListEntry[] entries) {
        for (PlayerListEntry entry : entries) {
            if (entry.getProfile() != null && entry.getProfile().getName() != null) {
                playerNames.put(entry.getProfileId(), entry.getProfile().getName());
            }
        }
    }

    public static void onPlayerRemove(Iterable<UUID> ids) {
        ids.forEach(playerNames::remove);
    }

    /**
     * Called for every signed player chat message.
     *
     * @param senderUuid the sender, from the chat packet
     * @param content    the plain message content
     */
    public static void onPlayerChat(UUID senderUuid, String content) {
        if (content == null) {
            return;
        }
        String message = content.trim();
        if (!message.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return;
        }
        if (message.length() > PREFIX.length() && message.charAt(PREFIX.length()) != ' ') {
            return; // e.g. "!botsfoo"
        }

        String sender = playerNames.get(senderUuid);
        if (sender == null) {
            Log.warn("Ignored a bot command from an unknown player (" + senderUuid + ")");
            return;
        }
        if (!admins.contains(sender.toLowerCase(Locale.ROOT))) {
            Log.warn("Ignored a bot command from " + sender + ", who is not in --admins");
            return;
        }

        Log.info(sender + " ran: " + message);
        execute(sender, message.substring(PREFIX.length()).trim());
    }

    private static void execute(String sender, String arguments) {
        String[] args = arguments.isEmpty() ? new String[0] : arguments.split("\\s+");
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";

        switch (sub) {
            case "bedrock", "be", "b" -> {
                Integer count = parseCount(sender, args, 1);
                if (count != null) {
                    reply(sender, Main.spawnBedrockBots(count));
                }
            }
            case "java", "je", "j" -> {
                Integer count = parseCount(sender, args, 1);
                if (count != null) {
                    reply(sender, Main.spawnJavaBots(count));
                }
            }
            case "leave", "exit", "kick" -> {
                BotClient.Edition edition = null;
                int countIndex = 1;
                if (args.length > 1) {
                    switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "java", "je", "j" -> {
                            edition = BotClient.Edition.JAVA;
                            countIndex = 2;
                        }
                        case "bedrock", "be", "b" -> {
                            edition = BotClient.Edition.BEDROCK;
                            countIndex = 2;
                        }
                        case "all" -> countIndex = 2;
                        default -> {
                        }
                    }
                }
                int limit = -1;
                if (args.length > countIndex) {
                    Integer count = parseCount(sender, args, countIndex);
                    if (count == null) {
                        return;
                    }
                    limit = count;
                }
                reply(sender, Main.disconnectBots(edition, limit));
            }
            case "list", "ls" -> reply(sender, Main.describeBots());
            default -> reply(sender, "Usage: " + PREFIX + " bedrock <n> | java <n> | leave [java|bedrock] [n] | list");
        }
    }

    private static Integer parseCount(String sender, String[] args, int index) {
        if (args.length <= index) {
            reply(sender, "Missing count");
            return null;
        }
        try {
            int count = Integer.parseInt(args[index]);
            if (count < 1 || count > MAX_PER_COMMAND) {
                reply(sender, "The count must be between 1 and " + MAX_PER_COMMAND);
                return null;
            }
            return count;
        } catch (NumberFormatException e) {
            reply(sender, "Invalid count: " + args[index]);
            return null;
        }
    }

    /** Answers privately in game, and in the console. */
    private static void reply(String sender, String message) {
        Log.info("[" + PREFIX + "] " + message);
        BotClient listener = Main.getListenerBot();
        if (listener != null) {
            listener.sendChat("/msg " + sender + " " + message);
        }
    }
}
