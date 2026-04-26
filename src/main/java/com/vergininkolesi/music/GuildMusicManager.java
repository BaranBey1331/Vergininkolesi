package com.vergininkolesi.music;

import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.Link;
import dev.arbjerg.lavalink.client.player.LavalinkPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public final class GuildMusicManager {
    private static final Logger LOG = LoggerFactory.getLogger(GuildMusicManager.class);

    private final long guildId;
    private final LavalinkClient lavalink;
    private final TrackScheduler scheduler;

    public GuildMusicManager(long guildId, LavalinkClient lavalink, int defaultVolume) {
        this.guildId = guildId;
        this.lavalink = lavalink;
        this.scheduler = new TrackScheduler(this, defaultVolume);
    }

    public TrackScheduler scheduler() {
        return scheduler;
    }

    public void stop() {
        scheduler.clear();
        getPlayer().ifPresent(player -> player.setPaused(false).setTrack(null)
            .subscribe(ignored -> { }, error -> LOG.warn("Failed to stop player for guild {}", guildId, error)));
    }

    public Optional<Link> getLink() {
        return Optional.ofNullable(lavalink.getLinkIfCached(guildId));
    }

    public Link getOrCreateLink() {
        return lavalink.getOrCreateLink(guildId);
    }

    public Optional<LavalinkPlayer> getPlayer() {
        return getLink().map(Link::getCachedPlayer);
    }
}
