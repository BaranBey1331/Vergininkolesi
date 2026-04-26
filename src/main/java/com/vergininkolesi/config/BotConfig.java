package com.vergininkolesi.config;

import io.github.cdimascio.dotenv.Dotenv;

public record BotConfig(
    String botToken,
    String lavalinkName,
    String lavalinkUri,
    String lavalinkPassword,
    String lavalinkRegion,
    boolean lavalinkAutostart,
    String lavalinkJar,
    long lavalinkStartupDelayMs,
    int defaultVolume
) {
    public static BotConfig load() {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMalformed()
            .ignoreIfMissing()
            .load();

        String token = read(dotenv, "BOT_TOKEN", "");
        if (token.isBlank()) {
            throw new IllegalStateException("BOT_TOKEN .env icinde veya ortam degiskenlerinde tanimli olmali.");
        }

        return new BotConfig(
            token,
            read(dotenv, "LAVALINK_NAME", "primary"),
            read(dotenv, "LAVALINK_URI", "ws://localhost:2333"),
            read(dotenv, "LAVALINK_PASSWORD", "youshallnotpass"),
            read(dotenv, "LAVALINK_REGION", "EUROPE"),
            Boolean.parseBoolean(read(dotenv, "LAVALINK_AUTOSTART", "true")),
            read(dotenv, "LAVALINK_JAR", "Lavalink.jar"),
            parseDelay(read(dotenv, "LAVALINK_STARTUP_DELAY_MS", "120000")),
            parseVolume(read(dotenv, "DEFAULT_VOLUME", "80"))
        );
    }

    private static String read(Dotenv dotenv, String key, String fallback) {
        String systemValue = System.getenv(key);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue.trim();
        }

        String dotenvValue = dotenv.get(key);
        if (dotenvValue != null && !dotenvValue.isBlank()) {
            return dotenvValue.trim();
        }

        return fallback;
    }

    private static int parseVolume(String value) {
        try {
            int volume = Integer.parseInt(value);
            return Math.max(0, Math.min(1000, volume));
        } catch (NumberFormatException ignored) {
            return 80;
        }
    }

    private static long parseDelay(String value) {
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 120_000L;
        }
    }
}
