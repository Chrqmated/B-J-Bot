package me.creepermaxcz.mcbots;

/**
 * Common behaviour of every bot, whatever edition it connects with.
 */
public interface BotClient {

    enum Edition {
        JAVA, BEDROCK
    }

    /** Starts connecting in the background. */
    void start();

    String getNickname();

    Edition getEdition();

    /** Sends a chat message, or a command when the text starts with "/". */
    void sendChat(String text);

    /** Moves the bot down by half a block to simulate gravity. */
    void fallDown();

    boolean isConnected();

    void disconnect();

    /** Whether this bot can print the chat and listen for in-game commands. */
    default boolean canListenToChat() {
        return false;
    }

    default void registerMainListener() {
    }

    default boolean hasMainListener() {
        return false;
    }
}
