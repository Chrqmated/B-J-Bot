# Minecraft bot stress tester
🤖 A simple open source app written in Java used for stress testing Minecraft servers with bots (fake players).  
💥 It can be also used to test plugins or minigames.  
✔️ Java bots: MC **26.2** — Bedrock bots: **26.60**, joining through Geyser over **NetherNet** (like real Bedrock players)  
For older MC versions please look in the [releases](https://github.com/crpmax/mc-bots/releases " releases").

## 🆒 Features
- ✅ Connect as many bots as you want
- ✅ Use SOCKS4 or SOCKS5 proxies from file or URL
- ✅ Receive colored or noncolored chat
- ✅ Set connection delay
- ✅ Set messages or commands on join
- ✅ Real Bedrock clients through Geyser (NetherNet built-in signaling + WebRTC), seen as Floodgate players
- ✅ Start Java or Bedrock bots from the game chat (`!bots bedrock 10`), restricted to `--admins`
- ✅ Generate random or real looking nicknames or load from file
- ✅ Online (premium) account support with login using Microsoft
- ✅ Control all or selected bots

## 📖 Usage
Minimal Java version: 21  
Use of pre-compiled jar from [releases](https://github.com/crpmax/mc-bots/releases " releases"):  
`java -jar mc-bots.jar -s <server address> [arguments]`  
When running, you can write a chat message to the terminal to send it by all bots.

## 🧪 Example
`java -jar mc-bots-2.0.0.jar -s 192.168.0.189:25565 -p BOT_ -d 4000 5000 -c 30 -r`  
This will connect 30 bots to server at 192.168.0.189:25565 with delay 4000-5000 ms and will use real-looking nicknames prefixed with BOT_

<img src="https://imgur.com/XWcckas.png" title="Connected bots" width="350px"/>
<img src="https://imgur.com/CvJq1Io.gif" title="Sending chat message by bots" width="350px"/>


## ⚡ Options
`-c <count>` The count of bots to connect, default is 1  
`-d <min> <max>` Set the minimum and maximum connection delay range in ms, default is 4000-5000  
`-j <message>` Messages or commands to send on join, separated by `&&`, does not work with `-m` or `-x` option  
`-p <prefix>` Custom bot nickname prefix eg. `BOT_`  
`-r` Generate real looking nicknames instead of random ones  
`-n` Do not use color in terminal - useful when the terminal does not support it  
`-m` Minimal run - do not use any listeners, will not receive chat, useful for large amounts of bots  
`-x` The most minimal run - No listeners, no control, no chat - useful for large amounts of bots for better performance  
`-t <type>` Set proxy type - SOCKS4 or SOCKS5  
`-l <path>` Set proxy list file  
`-g` Try to simulate gravity by falling down  
`-o` Use online (premium) account (login with Microsoft OAuth)  
`-ar <delay>` Set auto-respawn delay (default is 100 ms, set to -1 to disable)  
`--nicks <file>` Set nicknames file  
`--geyser <ip[:port]>` Geyser (Bedrock) address used by Bedrock bots, default is the server IP with port 19132  
`--admins <a,b,...>` Players allowed to use `!bots` in game (Bedrock players with their Floodgate name, e.g. `.Steve`)  


## Commands
Commands can be typed to the console. They are prefixed with `.` or `!`.
Without the prefix it will be sent as a chat message!  
`.list` or `.ls` - list all connected bots  
`.control <nick>` or `.ctrl <nick>` - select one or multiple bots to control  
`.exit <limit>` or `.leave <limit>` - disconnect all or specified number of bots   
`.java <n>` - connect n more Java bots  
`.bedrock <n>` - connect n Bedrock bots to Geyser  

## 🎮 In-game commands
With `--admins`, the bot reading the chat (the first Java bot) obeys these commands typed in the chat by an admin:  
`!bots bedrock <n>` - connect n Bedrock bots through Geyser  
`!bots java <n>` - connect n Java bots  
`!bots leave [java|bedrock] [n]` - disconnect bots  
`!bots list` - count the bots  
The sender is checked with the UUID of the signed player chat message, so it needs at least one Java bot (`-c 1`, the default), and does not work if a chat plugin turns player messages into system messages.  

## 🪨 Bedrock bots and Geyser
Bedrock bots join like a Bedrock player that is not signed in to Xbox Live, so Geyser must accept unsigned logins.  
In Geyser's `config.yml` (**test servers only**, it lets anyone pick any Bedrock name):
```yaml
bedrock:
  transport: nethernet   # or both
  signaling:
    mode: builtin        # or hybrid; the bots use built-in signaling
advanced:
  bedrock:
    validate-bedrock-login: false
```
Example: `java -jar mc-bots-2.0.0.jar -s play.example.com --geyser play.example.com:25665 --admins Steve,.BedrockSteve`, then type `!bots bedrock 10` in game.  
NetherNet uses [libdatachannel](https://github.com/paullouisageneau/libdatachannel); the jar ships it for Windows x64, Linux x64/arm64 (glibc) and macOS arm64.

## ⚠ DISCLAIMER
**This app is made for educational and testing purposes only.  
This app can be used like spambots.  
I am not responsible for any abuse.**
