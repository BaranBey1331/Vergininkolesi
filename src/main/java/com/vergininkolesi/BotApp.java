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

import java.net.URI;

public final class BotApp {
    private static final Logger LOG = LoggerFactory.getLogger(BotApp.class);
    private static final int SESSION_INVALID = 4006;

    private BotApp() {
    }

    public static void main(String[] args) throws InterruptedException {
        BotConfig config = BotConfig.load();
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
}
