package com.vergininkolesi.music;

import dev.arbjerg.lavalink.client.player.Track;
import dev.arbjerg.lavalink.protocol.v4.Message;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TrackScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(TrackScheduler.class);

    private final GuildMusicManager manager;
    private final int defaultVolume;
    private final Queue<Track> queue = new LinkedList<>();

    TrackScheduler(GuildMusicManager manager, int defaultVolume) {
        this.manager = manager;
        this.defaultVolume = defaultVolume;
    }

    public synchronized QueueResult enqueue(Track track) {
        boolean shouldStart = manager.getPlayer()
            .map(player -> player.getTrack() == null)
            .orElse(true);

        if (shouldStart) {
            startTrack(track);
            return new QueueResult(true, queue.size());
        }

        queue.offer(track);
        return new QueueResult(false, queue.size());
    }

    public synchronized int enqueuePlaylist(List<Track> tracks, long requesterId) {
        tracks.forEach(track -> {
            track.setUserData(new QueuedTrack(requesterId));
            queue.offer(track);
        });

        manager.getPlayer().ifPresentOrElse(player -> {
            if (player.getTrack() == null) {
                startTrack(queue.poll());
            }
        }, () -> startTrack(queue.poll()));

        return tracks.size();
    }

    public synchronized SkipResult skip() {
        boolean hasCurrentTrack = manager.getPlayer()
            .map(player -> player.getTrack() != null)
            .orElse(false);

        if (!hasCurrentTrack && queue.isEmpty()) {
            return SkipResult.NOTHING_PLAYING;
        }

        Track next = queue.poll();
        if (next == null) {
            manager.getPlayer().ifPresent(player -> player.setTrack(null).setPaused(false)
                .subscribe(ignored -> { }, error -> LOG.warn("Failed to stop player while skipping", error)));
            return SkipResult.STOPPED;
        }

        startTrack(next);
        return SkipResult.NEXT_TRACK;
    }

    public synchronized void onTrackEnd(Message.EmittedEvent.TrackEndEvent.AudioTrackEndReason endReason) {
        if (!endReason.getMayStartNext()) {
            return;
        }

        Track next = queue.poll();
        if (next != null) {
            startTrack(next);
        }
    }

    public synchronized void clear() {
        queue.clear();
    }

    public synchronized List<Track> snapshot(int limit) {
        return queue.stream().limit(limit).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    public synchronized int size() {
        return queue.size();
    }

    private void startTrack(Track track) {
        if (track == null) {
            return;
        }

        manager.getOrCreateLink()
            .createOrUpdatePlayer()
            .setTrack(track)
            .setVolume(defaultVolume)
            .setPaused(false)
            .subscribe(ignored -> { }, error -> LOG.warn("Failed to start track '{}'", track.getInfo().getTitle(), error));
    }

    public record QueueResult(boolean started, int queueSize) {
    }

    public enum SkipResult {
        NEXT_TRACK,
        STOPPED,
        NOTHING_PLAYING
    }
}
