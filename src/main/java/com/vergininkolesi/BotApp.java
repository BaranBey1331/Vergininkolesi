package com.vergininkolesi;

import com.vergininkolesi.config.BotConfig;
import com.vergininkolesi.music.MusicListener;
import dev.arbjerg.lavalink.client.Helpers;
import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.LavalinkNode;
import dev.arbjerg.lavalink.client.NodeOptions;
import dev.arbjerg.lavalink.client.event.ReadyEvent;
import dev.arbjerg.lavalink.client.event.StatsEvent;
import dev.arbjerg.lavalink.client.event.TrackEndEvent;
import dev.arbjerg.lavalink.client.event.WebSocketClosedEvent;
import dev.arbjerg.lavalink.client.loadbalancing.RegionGroup;
import dev.arbjerg.lavalink.client.loadbalancing.builtin.VoiceRegionPenaltyProvider;
import dev.arbjerg.lavalink.libraries.jda.JDAVoiceUpdateListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public final class BotApp {
    private static final Logger LOG = LoggerFactory.getLogger(BotApp.class);
    private static final int SESSION_INVALID = 4006;

    private BotApp() {
    }

    public static void main(String[] args) throws InterruptedException {
        BotConfig config = BotConfig.load();
        ensureLavalinkBackend(config);

        LavalinkClient lavalink = new LavalinkClient(Helpers.getUserIdFromToken(config.botToken()));
        MusicListener musicListener = new MusicListener(lavalink, config.defaultVolume());

        lavalink.getLoadBalancer().addPenaltyProvider(new VoiceRegionPenaltyProvider());
        registerLavalinkNode(lavalink, config);
        registerLavalinkListeners(lavalink, musicListener);

        JDA jda = JDABuilder.createDefault(config.botToken())
            .setVoiceDispatchInterceptor(new JDAVoiceUpdateListener(lavalink))
            .enableIntents(GatewayIntent.GUILD_VOICE_STATES)
            .enableCache(CacheFlag.VOICE_STATE)
            .addEventListeners(musicListener)
            .build()
            .awaitReady();

        lavalink.on(WebSocketClosedEvent.class).subscribe(event -> {
            if (event.getCode() != SESSION_INVALID) {
                return;
            }

            var guild = jda.getGuildById(event.getGuildId());
            if (guild == null || guild.getSelfMember().getVoiceState() == null) {
                return;
            }

            var channel = guild.getSelfMember().getVoiceState().getChannel();
            if (channel != null) {
                jda.getDirectAudioController().reconnect(channel);
            }
        });
    }

    private static void registerLavalinkNode(LavalinkClient lavalink, BotConfig config) {
        LOG.info("Connecting to Lavalink node '{}' at {}", config.lavalinkName(), config.lavalinkUri());

        LavalinkNode node = lavalink.addNode(
            new NodeOptions.Builder()
                .setName(config.lavalinkName())
                .setServerUri(URI.create(config.lavalinkUri()))
                .setPassword(config.lavalinkPassword())
                .setRegionFilter(RegionGroup.INSTANCE.valueOf(config.lavalinkRegion()))
                .setHttpTimeout(10_000L)
                .build()
        );

        node.on(dev.arbjerg.lavalink.client.event.TrackStartEvent.class)
            .subscribe(event -> LOG.info("Track started on {}: {}", node.getName(), event.getTrack().getInfo().getTitle()));
    }

    private static void registerLavalinkListeners(LavalinkClient lavalink, MusicListener musicListener) {
        lavalink.on(ReadyEvent.class).subscribe(event -> LOG.info(
            "Lavalink node '{}' ready, session {}",
            event.getNode().getName(),
            event.getSessionId()
        ));

        lavalink.on(StatsEvent.class).subscribe(event -> LOG.debug(
            "Lavalink node '{}' players: {}/{}",
            event.getNode().getName(),
            event.getPlayingPlayers(),
            event.getPlayers()
        ));

        lavalink.on(TrackEndEvent.class).subscribe(musicListener::onTrackEnd);
    }

    private static void ensureLavalinkBackend(BotConfig config) {
        String versionUrl = toHttpUri(config.lavalinkUri()).resolve("/version").toString();
        if (isLavalinkReady(versionUrl)) {
            LOG.info("Lavalink is already ready at {}", config.lavalinkUri());
            return;
        }

        if (!config.lavalinkAutostart()) {
            LOG.warn("LAVALINK_AUTOSTART=false and Lavalink is not ready at {}.", config.lavalinkUri());
            return;
        }

        File lavalinkJar = new File(config.lavalinkJar());
        if (!lavalinkJar.isFile()) {
            throw new IllegalStateException("LAVALINK_AUTOSTART=true but '" + config.lavalinkJar() + "' was not found.");
        }

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(lavalinkJar.getPath());
        if (new File("application.yml").isFile()) {
            command.add("--spring.config.additional-location=file:./application.yml");
        }

        try {
            Process process = new ProcessBuilder(command)
                .inheritIO()
                .start();

            Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
            LOG.info("Bundled Lavalink started from '{}'. Waiting for node readiness...", lavalinkJar.getPath());
            if (!waitForBundledLavalink(config, process, versionUrl)) {
                throw new IllegalStateException("Bundled Lavalink did not become ready at " + config.lavalinkUri());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start bundled Lavalink jar '" + lavalinkJar.getPath() + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for bundled Lavalink startup.", e);
        }
    }

    private static boolean waitForBundledLavalink(BotConfig config, Process process, String versionUrl) throws InterruptedException {
        long deadline = System.currentTimeMillis() + config.lavalinkStartupDelayMs();

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                LOG.error("Bundled Lavalink exited before becoming ready with code {}. Check application.yml and plugin logs above.", process.exitValue());
                return false;
            }

            if (isLavalinkReady(versionUrl)) {
                LOG.info("Bundled Lavalink is ready at {}", config.lavalinkUri());
                monitorLavalinkProcess(process);
                return true;
            }

            Thread.sleep(1_000L);
        }

        LOG.error("Bundled Lavalink did not answer at {} within {} ms.", versionUrl, config.lavalinkStartupDelayMs());
        return false;
    }

    private static boolean isLavalinkReady(String versionUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(versionUrl).openConnection();
            connection.setConnectTimeout(1_000);
            connection.setReadTimeout(1_000);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == 200;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void monitorLavalinkProcess(Process process) {
        Thread monitor = new Thread(() -> {
            try {
                int code = process.waitFor();
                LOG.error("Bundled Lavalink process exited with code {}. Playback will stop until the server is restarted.", code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "lavalink-process-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private static URI toHttpUri(String lavalinkUri) {
        URI uri = URI.create(lavalinkUri);
        String scheme = "wss".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
        return URI.create(scheme + "://" + uri.getAuthority());
    }
}
