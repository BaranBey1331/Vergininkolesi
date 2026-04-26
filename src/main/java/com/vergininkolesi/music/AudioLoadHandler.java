package com.vergininkolesi.music;

import dev.arbjerg.lavalink.client.AbstractAudioLoadResultHandler;
import dev.arbjerg.lavalink.client.player.LoadFailed;
import dev.arbjerg.lavalink.client.player.PlaylistLoaded;
import dev.arbjerg.lavalink.client.player.SearchResult;
import dev.arbjerg.lavalink.client.player.Track;
import dev.arbjerg.lavalink.client.player.TrackLoaded;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.List;

public final class AudioLoadHandler extends AbstractAudioLoadResultHandler {
    private static final Color SPOTIFY_GREEN = new Color(30, 215, 96);

    private final SlashCommandInteractionEvent event;
    private final GuildMusicManager manager;
    private final long requesterId;

    public AudioLoadHandler(SlashCommandInteractionEvent event, GuildMusicManager manager) {
        this.event = event;
        this.manager = manager;
        this.requesterId = event.getUser().getIdLong();
    }

    @Override
    public void ontrackLoaded(@NotNull TrackLoaded result) {
        Track track = withRequester(result.getTrack());
        TrackScheduler.QueueResult queueResult = manager.scheduler().enqueue(track);
        event.getHook().sendMessageEmbeds(trackEmbed(
            queueResult.started() ? "Calmaya basladi" : "Siraya eklendi",
            track,
            queueResult.queueSize()
        ).build()).queue();
    }

    @Override
    public void onPlaylistLoaded(@NotNull PlaylistLoaded result) {
        int count = manager.scheduler().enqueuePlaylist(result.getTracks(), requesterId);
        event.getHook().sendMessageEmbeds(new EmbedBuilder()
            .setColor(SPOTIFY_GREEN)
            .setTitle("Playlist siraya eklendi")
            .setDescription("**%s** icinden `%d` parca eklendi.".formatted(result.getInfo().getName(), count))
            .build()).queue();
    }

    @Override
    public void onSearchResultLoaded(@NotNull SearchResult result) {
        List<Track> tracks = result.getTracks();
        if (tracks.isEmpty()) {
            noMatches();
            return;
        }

        Track track = withRequester(tracks.get(0));
        TrackScheduler.QueueResult queueResult = manager.scheduler().enqueue(track);
        event.getHook().sendMessageEmbeds(trackEmbed(
            queueResult.started() ? "Calmaya basladi" : "Siraya eklendi",
            track,
            queueResult.queueSize()
        ).build()).queue();
    }

    @Override
    public void noMatches() {
        event.getHook().sendMessage("Sonuc bulunamadi. Link, YouTube/Spotify URL veya arama metni deneyin.").queue();
    }

    @Override
    public void loadFailed(@NotNull LoadFailed result) {
        String message = result.getException() == null ? "Bilinmeyen Lavalink hatasi." : result.getException().getMessage();
        event.getHook().sendMessage("Parca yuklenemedi: " + message).queue();
    }

    private Track withRequester(Track track) {
        track.setUserData(new QueuedTrack(requesterId));
        return track;
    }

    private EmbedBuilder trackEmbed(String title, Track track, int queueSize) {
        var info = track.getInfo();
        String url = info.getUri();
        EmbedBuilder embed = new EmbedBuilder()
            .setColor(SPOTIFY_GREEN)
            .setTitle(title)
            .setDescription("[%s](%s)".formatted(info.getTitle(), url == null ? "" : url))
            .addField("Sanatci/Kaynak", nullToDash(info.getAuthor()), true)
            .addField("Sure", formatMillis(info.getLength()), true)
            .addField("Sira", queueSize == 0 ? "Simdi" : String.valueOf(queueSize), true)
            .setFooter("Istek: " + event.getUser().getName(), event.getUser().getEffectiveAvatarUrl());

        if (info.getArtworkUrl() != null && !info.getArtworkUrl().isBlank()) {
            embed.setThumbnail(info.getArtworkUrl());
        }

        return embed;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    static String formatMillis(long millis) {
        if (millis <= 0) {
            return "Canli";
        }

        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        if (hours > 0) {
            return "%d:%02d:%02d".formatted(hours, minutes, remainingSeconds);
        }

        return "%d:%02d".formatted(minutes, remainingSeconds);
    }
}
