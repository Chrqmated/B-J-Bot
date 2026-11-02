package me.creepermaxcz.mcbots;

import me.creepermaxcz.mcbots.bedrock.BedrockBot;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import org.apache.commons.cli.*;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.ProxyInfo;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.Type;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;


public class Main {

    /** Floodgate prefixes Bedrock names (with "." by default) and Java names are limited to 16 characters */
    private static final int BEDROCK_NICK_MAX_LENGTH = 15;
    private static final int DEFAULT_BEDROCK_PORT = 19132;

    public static boolean coloredChat = true;
    static final List<BotClient> bots = new CopyOnWriteArrayList<>();
    private static final AtomicInteger pendingSpawns = new AtomicInteger();
    private static final SecureRandom random = new SecureRandom();
    private static int delayMin = 4000;
    private static int delayMax = 5000;
    private static boolean minimal = false;
    private static boolean mostMinimal = false;
    public static final ArrayList<String> joinMessages = new ArrayList<>();

    public static int autoRespawnDelay = 100;

    private static boolean useProxies = false;
    private static final ArrayList<InetSocketAddress> proxies = new ArrayList<>();
    private static int proxyIndex = 0;
    private static int proxyCount = 0;
    private static ProxyInfo.Type proxyType;

    private static final Set<BotClient> controlledBots = ConcurrentHashMap.newKeySet();

    private static InetSocketAddress javaAddress;
    private static InetSocketAddress bedrockAddress;
    private static NickGenerator nickGen;
    /** Online (premium) account, null in offline mode */
    private static MinecraftProtocol onlineProtocol;

    /** Spawns bots one after another, with the connection delay between them */
    private static final ExecutorService spawner = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "bot-spawner");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "bot-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    public static void main(String[] args) throws Exception {

        Options options = new Options();

        options.addOption("c", "count", true, "Java bot count");

        Option addressOption = new Option("s", "server", true, "Java server IP[:port]");
        addressOption.setRequired(true);
        options.addOption(addressOption);

        options.addOption(null, "geyser", true,
                "Geyser (Bedrock) IP[:port] used by Bedrock bots, default is the server IP with port " + DEFAULT_BEDROCK_PORT);
        options.addOption(null, "admins", true,
                "Comma separated players allowed to control the bots with " + ChatCommands.PREFIX + " in game");

        Option delayOption = new Option("d", "delay", true, "connection delay (ms) <min> <max>");
        delayOption.setArgs(2);
        options.addOption(delayOption);

        options.addOption("r", "real", false, "generate real looking nicknames");
        options.addOption("n", "nocolor", false, "dont color & format incoming chat messages");
        options.addOption("p", "prefix", true, "bot nick prefix");
        options.addOption("m", "minimal", false, "minimal run without any listeners");
        options.addOption("x", "most-minimal", false, "minimal run without any control, just connect the bots");
        options.addOption("j", "join-msg", true, "join messages / commands, separated by &&");

        options.addOption("l", "proxy-list", true, "Path or URL to proxy list file with proxy:port on every line");
        options.addOption("t", "proxy-type", true, "Proxy type: SOCKS4 or SOCKS5");

        options.addOption(null, "nicks", true, "Path to nicks file with nick on every line");

        options.addOption("g", "gravity", false, "Try to simulate gravity by falling down");

        options.addOption("o", "online", false, "Use online mode (premium) account");

        options.addOption("ar", "auto-respawn", true, "Set autorespawn delay (-1 to disable)");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("mcbots", e.getMessage(), options, "\nhttps://github.com/crpmax/mc-bots", true);
            System.exit(1);
        }

        autoRespawnDelay = Integer.parseInt(cmd.getOptionValue("ar", "100"));

        if (cmd.hasOption('t') && cmd.hasOption('l')) {
            loadProxies(cmd.getOptionValue('t'), cmd.getOptionValue('l'));
        }

        int botCount = Integer.parseInt(cmd.getOptionValue('c', "1"));

        minimal = cmd.hasOption('m');
        if (cmd.hasOption('x')) {
            minimal = mostMinimal = true;
        }

        if (cmd.hasOption('d')) {
            String[] delays = cmd.getOptionValues('d');
            delayMin = Integer.parseInt(delays[0]);
            delayMax = delayMin + 1;
            if (delays.length == 2) {
                delayMax = Integer.parseInt(delays[1]);
            }
            if (delayMax <= delayMin) {
                throw new IllegalArgumentException("delay max must not be equal or lower than delay min");
            }
        }

        coloredChat = !cmd.hasOption('n');

        if (cmd.hasOption('j')) {
            // Split messages by &&, trim and append to arraylist
            for (String msg : cmd.getOptionValue('j').split("&&")) {
                joinMessages.add(msg.trim());
            }
        }

        if (cmd.hasOption("admins")) {
            ChatCommands.setAdmins(cmd.getOptionValue("admins"));
        }

        javaAddress = resolveJavaAddress(cmd.getOptionValue('s'));
        bedrockAddress = resolveAddress(cmd.getOptionValue("geyser", javaAddress.getHostString()), DEFAULT_BEDROCK_PORT);

        nickGen = new NickGenerator();

        if (cmd.hasOption("nicks")) {
            Log.info("Loading nicknames from specified file");
            int nicksCount = nickGen.loadFromFile(cmd.getOptionValue("nicks"));

            if (nicksCount == 0) {
                Log.error("No valid nicknames loaded");
                System.exit(1);
            }

            if (nicksCount < botCount) {
                Log.warn("Nickname count is lower than bot count!");
                Thread.sleep(3000);
            }
        } else {
            nickGen.setReal(cmd.hasOption('r'));
        }

        nickGen.setPrefix(cmd.getOptionValue('p', ""));

        //print info
        Log.info("Java server:", javaAddress.getHostString() + ":" + javaAddress.getPort());
        Log.info("Geyser (Bedrock):", bedrockAddress.getHostString() + ":" + bedrockAddress.getPort());
        Log.info("Java bot count: " + botCount);
        if (ChatCommands.isEnabled()) {
            Log.info("In-game commands (" + ChatCommands.PREFIX + ") allowed for: " + String.join(", ", ChatCommands.getAdmins()));
        } else {
            Log.info("In-game commands are disabled, use --admins <names> to allow players to use " + ChatCommands.PREFIX);
        }

        //get and print server info
        ServerInfo serverInfo = new ServerInfo(javaAddress);
        serverInfo.requestInfo();
        ServerStatusInfo statusInfo = serverInfo.getStatusInfo();
        if (statusInfo != null) {
            Log.info(
                    "Server version: "
                            + statusInfo.getVersionInfo().getVersionName()
                            + " (" + statusInfo.getVersionInfo().getProtocolVersion()
                            + ")"
            );
            Log.info("Player Count: " + statusInfo.getPlayerInfo().getOnlinePlayers()
                    + " / " + statusInfo.getPlayerInfo().getMaxPlayers());
            Log.info();
        } else {
            Log.warn("There was an error retrieving server status information. The server may be offline or running on a different version.");
        }

        if (cmd.hasOption("o")) {
            Log.warn("Online mode enabled. The Java bot count will be set to 1.");
            botCount = Math.min(botCount, 1);
            onlineProtocol = loginOnline();
        }

        if (ChatCommands.isEnabled() && botCount == 0) {
            Log.warn("No Java bot will connect, so nobody will read the in-game commands. Use -c 1 or more.");
        }
        if (ChatCommands.isEnabled() && minimal) {
            Log.warn("In-game commands do not work with -m or -x, as no bot listens to the chat.");
        }

        spawnJavaBots(botCount);

        //gravity timer
        if (cmd.hasOption("g")) {
            scheduler.scheduleAtFixedRate(() -> bots.forEach(BotClient::fallDown), 1000L, 500L, TimeUnit.MILLISECONDS);
        }

        runConsole();
    }

    // ------------------------------------------------------------------------------------------- setup

    private static MinecraftProtocol loginOnline() throws Exception {
        HttpClient httpClient = MinecraftAuth.createHttpClient();
        JavaAuthManager authManager = JavaAuthManager.create(httpClient).login(DeviceCodeMsaAuthService::new,
                (Consumer<MsaDeviceCode>) msaDeviceCode -> {
                    Log.info("Authorize your Microsoft account on " + msaDeviceCode.getDirectVerificationUri());
                    Log.info("Waiting for authorization.");
                });

        MinecraftProfile profile = authManager.getMinecraftProfile().getUpToDate();
        Log.info("Logged in with username: " + profile.getName());

        GameProfile gameProfile = new GameProfile(profile.getId(), profile.getName());
        return new MinecraftProtocol(gameProfile, authManager.getMinecraftToken().getUpToDate().getToken());
    }

    private static void loadProxies(String type, String proxyPath) {
        //get proxy type
        try {
            proxyType = ProxyInfo.Type.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Log.error("Invalid proxy type, use SOCKS4 or SOCKS5.");
            System.exit(1);
        }

        //read proxy list file
        try {
            BufferedReader reader;
            try {
                //try to read specified path as URL
                reader = new BufferedReader(new InputStreamReader(URI.create(proxyPath).toURL().openStream()));
                Log.info("Reading proxies from URL");
            } catch (IllegalArgumentException | MalformedURLException e) {
                Log.info("Specified proxy file is not a URL, trying to read file");
                reader = new BufferedReader(new FileReader(proxyPath));
            }

            try (BufferedReader read = reader) {
                String line;
                while ((line = read.readLine()) != null) {
                    try {
                        String[] parts = line.trim().split(":");
                        if (parts.length == 2) {
                            proxies.add(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
                            proxyCount++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (IOException e) {
            Log.error("Invalid proxy list file path.");
            System.exit(1);
        }

        if (proxyCount > 0) {
            useProxies = true;
            Log.info("Loaded " + proxyCount + " valid proxies");
        } else {
            Log.error("No valid proxies loaded");
            System.exit(1);
        }
    }

    private static InetSocketAddress resolveJavaAddress(String address) throws Exception {
        if (address.contains(":")) {
            return resolveAddress(address, 25565);
        }
        String host = address;
        int port = 25565;
        Record[] records = new Lookup("_minecraft._tcp." + address, Type.SRV).run();
        if (records != null) {
            for (Record record : records) {
                SRVRecord srv = (SRVRecord) record;
                host = srv.getTarget().toString().replaceFirst("\\.$", "");
                port = srv.getPort();
            }
        }
        return new InetSocketAddress(InetAddress.getByName(host).getHostAddress(), port);
    }

    private static InetSocketAddress resolveAddress(String address, int defaultPort) throws UnknownHostException {
        String host = address;
        int port = defaultPort;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) {
            host = address.substring(0, colon);
            port = Integer.parseInt(address.substring(colon + 1));
        }
        return new InetSocketAddress(InetAddress.getByName(host).getHostAddress(), port);
    }

    private static synchronized ProxyInfo nextProxy() {
        if (!useProxies) {
            return null;
        }
        InetSocketAddress proxySocket = proxies.get(proxyIndex);
        if (!minimal) {
            Log.info("Using proxy: (" + proxyIndex + ")", proxySocket.getHostString() + ":" + proxySocket.getPort());
        }
        proxyIndex = (proxyIndex + 1) % proxyCount;
        return new ProxyInfo(proxyType, proxySocket);
    }

    // ------------------------------------------------------------------------------------------- spawning

    /**
     * Queues Java bots to connect, one after another with the connection delay.
     *
     * @return a message describing what was done
     */
    public static String spawnJavaBots(int count) {
        if (count <= 0) {
            return "Nothing to do";
        }
        if (onlineProtocol != null && bots.stream().anyMatch(bot -> bot.getEdition() == BotClient.Edition.JAVA)) {
            return "Online mode only supports a single Java bot";
        }
        int actual = onlineProtocol != null ? 1 : count;
        queueSpawns(actual, () -> {
            MinecraftProtocol protocol = onlineProtocol != null ? onlineProtocol : new MinecraftProtocol(nickGen.nextNick());
            return new Bot(protocol, javaAddress, nextProxy());
        });
        return "Connecting " + actual + " Java bot(s) to " + javaAddress.getHostString() + ":" + javaAddress.getPort();
    }

    /**
     * Queues Bedrock bots to connect to Geyser over NetherNet, one after another with the connection delay.
     *
     * @return a message describing what was done
     */
    public static String spawnBedrockBots(int count) {
        if (count <= 0) {
            return "Nothing to do";
        }
        try {
            BedrockBot.initNative();
        } catch (Throwable t) {
            Log.error("NetherNet is not available on this platform: " + t);
            return "Bedrock bots are not supported here (libdatachannel failed to load)";
        }
        queueSpawns(count, () -> new BedrockBot(nickGen.nextNick(BEDROCK_NICK_MAX_LENGTH), bedrockAddress));
        return "Connecting " + count + " Bedrock bot(s) to Geyser " + bedrockAddress.getHostString() + ":" + bedrockAddress.getPort();
    }

    private static void queueSpawns(int count, Callable<BotClient> factory) {
        pendingSpawns.addAndGet(count);
        for (int i = 0; i < count; i++) {
            spawner.execute(() -> {
                try {
                    BotClient bot = factory.call();
                    if (!mostMinimal) bots.add(bot);
                    bot.start();

                    if (bot.canListenToChat() && !isMinimal() && getListenerBot() == null) {
                        bot.registerMainListener();
                    }
                } catch (Exception e) {
                    Log.error(e);
                } finally {
                    pendingSpawns.decrementAndGet();
                }

                if (pendingSpawns.get() > 0) {
                    long delay = getRandomDelay();
                    Log.info("Waiting", delay + "", "ms");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
    }

    public static void sendJoinMessages(BotClient bot) {
        if (joinMessages.isEmpty()) {
            return;
        }
        scheduler.execute(() -> {
            for (String msg : joinMessages) {
                bot.sendChat(msg);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        });
    }

    public static void schedule(Runnable task, long delayMs) {
        scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------------------------------- control

    /**
     * Disconnects bots, the controlled ones if some are selected.
     *
     * @param edition only bots of this edition, or null for all
     * @param limit   maximum number of bots, or -1 for all
     * @return a message describing what was done
     */
    public static String disconnectBots(BotClient.Edition edition, int limit) {
        Collection<BotClient> source = controlledBots.isEmpty() ? bots : controlledBots;
        List<BotClient> targets = source.stream()
                .filter(bot -> edition == null || bot.getEdition() == edition)
                // Keep the bot reading the chat until the end, so in-game commands keep working
                .sorted(Comparator.comparing(BotClient::hasMainListener))
                .limit(limit < 0 ? Long.MAX_VALUE : limit)
                .collect(Collectors.toList());
        targets.forEach(BotClient::disconnect);
        return "Disconnecting " + targets.size() + " bot(s)";
    }

    public static String describeBots() {
        long java = bots.stream().filter(bot -> bot.getEdition() == BotClient.Edition.JAVA).count();
        long bedrock = bots.size() - java;
        long online = bots.stream().filter(BotClient::isConnected).count();
        return bots.size() + " bot(s): " + java + " Java, " + bedrock + " Bedrock, " + online + " in game"
                + (pendingSpawns.get() > 0 ? ", " + pendingSpawns.get() + " waiting to connect" : "");
    }

    public static BotClient getListenerBot() {
        for (BotClient bot : bots) {
            if (bot.hasMainListener()) {
                return bot;
            }
        }
        return null;
    }

    private static void runConsole() throws Exception {
        Terminal terminal = TerminalBuilder.builder().build();
        LineReader lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(new org.jline.reader.impl.DefaultParser())
                .build();

        while (true) {
            String line;
            try {
                line = lineReader.readLine();
            } catch (UserInterruptException e) {
                System.exit(0);
                break;
            } catch (EndOfFileException e) {
                // stdin closed (e.g. running in the background): keep the bots running without a console
                Thread.currentThread().join();
                return;
            }
            if (line.isEmpty()) {
                System.out.print(getPrompt() + "> ");
                continue;
            }

            if (line.startsWith("!") || line.startsWith(".")) {
                handleConsoleCommand(line.substring(1).split(" "));
            } else if (!controlledBots.isEmpty()) {
                controlledBots.forEach(bot -> bot.sendChat(line));
            } else {
                bots.forEach(bot -> bot.sendChat(line));
            }

            Thread.sleep(50);
        }
    }

    private static void handleConsoleCommand(String[] split) {
        String commandName = split[0];

        // Bot control selection
        if (commandName.equalsIgnoreCase("control") || commandName.equalsIgnoreCase("ctrl")) {
            controlledBots.clear();
            if (split.length < 2) {
                // If no bot names are supplied, control all bots
                Log.info("No bots selected - now controlling all bots.");
                return;
            }

            // Search for all bot names, from index 1 - skip command name
            for (int i = 1; i < split.length; i++) {
                BotClient bot = findBotByName(split[i]);
                if (bot != null) {
                    controlledBots.add(bot);
                } else {
                    Log.warn("Bot not found: " + split[i]);
                }
            }

            if (controlledBots.isEmpty()) {
                Log.warn("No bots found.");
            } else {
                String botNames = controlledBots.stream().map(BotClient::getNickname).collect(Collectors.joining(", "));
                Log.info("Now controlling " + controlledBots.size() + " bot(s): " + botNames);
            }
        } else if (commandName.equalsIgnoreCase("list") || commandName.equalsIgnoreCase("ls")) {
            Log.info(describeBots());
            for (BotClient bot : bots) {
                Log.info(bot.getNickname(), "[" + bot.getEdition() + "]",
                        bot.isConnected() ? "" : "[connecting]",
                        bot.hasMainListener() ? "[MainListener]" : "");
            }
        } else if (commandName.equalsIgnoreCase("leave") || commandName.equalsIgnoreCase("exit")) {
            int limit = -1;
            if (split.length >= 2) {
                try {
                    limit = Integer.parseInt(split[1]);
                } catch (NumberFormatException e) {
                    Log.warn("Invalid limit.");
                    return;
                }
            }
            Log.info(disconnectBots(null, limit));
        } else if (commandName.equalsIgnoreCase("bedrock") || commandName.equalsIgnoreCase("java")) {
            int count = 1;
            if (split.length >= 2) {
                try {
                    count = Integer.parseInt(split[1]);
                } catch (NumberFormatException e) {
                    Log.warn("Invalid count.");
                    return;
                }
            }
            Log.info(commandName.equalsIgnoreCase("bedrock") ? spawnBedrockBots(count) : spawnJavaBots(count));
        } else {
            Log.warn("Invalid command. Commands: .list, .ctrl [names], .leave [n], .java [n], .bedrock [n]");
        }
    }

    public static synchronized void removeBot(BotClient bot) {
        bots.remove(bot);
        controlledBots.remove(bot);
        if (bot.hasMainListener()) {
            Log.info("Bot with MainListener removed");
            if (!isMinimal()) {
                // Give the chat listener to another Java bot
                for (BotClient other : bots) {
                    if (other.canListenToChat()) {
                        Log.info("Renewing MainListener");
                        other.registerMainListener();
                        break;
                    }
                }
            }
        }
        if (bots.isEmpty() && pendingSpawns.get() == 0) {
            Log.error("All bots disconnected, exiting");
            System.exit(0);
        }
    }

    public static long getRandomDelay() {
        return random.nextInt(delayMax - delayMin) + delayMin;
    }

    public static boolean isMinimal() {
        return minimal;
    }

    public static BotClient findBotByName(String text) {
        for (BotClient bot : bots) {
            // Starts with and ignore case
            if (bot.getNickname().regionMatches(true, 0, text, 0, text.length())) {
                return bot;
            }
        }
        return null;
    }

    public static String getPrompt() {
        int count = controlledBots.size();
        if (count == 0) {
            return "ALL";
        } else if (count == 1) {
            //If controlling only one bot, return its nickname
            return controlledBots.iterator().next().getNickname();
        } else {
            return count + " BOTS";
        }
    }
}
