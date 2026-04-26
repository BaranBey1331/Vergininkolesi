package com.vergininkolesi.music;

import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.event.TrackEndEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MusicListener extends ListenerAdapter {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern JSON_TITLE = Pattern.compile("\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final LavalinkClient lavalink;
    private final int defaultVolume;
    private final Map<Long, GuildMusicManager> musicManagers = new ConcurrentHashMap<>();

    public MusicListener(LavalinkClient lavalink, int defaultVolume) {
        this.lavalink = lavalink;
        this.defaultVolume = defaultVolume;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        event.getJDA().updateCommands()
            .addCommands(
                Commands.slash("play", "Sarki, video, playlist veya Spotify linki calar")
                    .addOption(OptionType.STRING, "query", "URL veya arama metni", true),
                Commands.slash("skip", "Siradaki parcaya gecer"),
                Commands.slash("stop", "Calmayi durdurur ve sirayi temizler"),
                Commands.slash("pause", "Calan parcayi duraklatir"),
                Commands.slash("resume", "Duraklatilan parcayi devam ettirir"),
                Commands.slash("nowplaying", "Su an calan parcayi gosterir"),
                Commands.slash("queue", "Siradaki parcalari gosterir"),
                Commands.slash("volume", "Ses seviyesini ayarlar")
                    .addOption(OptionType.INTEGER, "level", "0-1000 arasi seviye", true),
                Commands.slash("leave", "Ses kanalindan ayrilir")
            )
            .queue();
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            event.replyEmbeds(MusicEmbeds.warning(
                "Sunucu gerekli",
                "Bu bot komutlari sadece Discord sunucusu icinde kullanilir."
            ).build()).setEphemeral(true).queue();
            return;
        }

        switch (event.getFullCommandName()) {
            case "play" -> play(event, guild);
            case "skip" -> skip(event, guild);
            case "stop" -> stop(event, guild);
            case "pause" -> pause(event, guild, true);
            case "resume" -> pause(event, guild, false);
            case "nowplaying" -> nowPlaying(event, guild);
            case "queue" -> queue(event, guild);
            case "volume" -> volume(event, guild);
            case "leave" -> leave(event, guild);
            default -> event.replyEmbeds(MusicEmbeds.warning("Bilinmeyen komut", "Bu komut tanimli degil.").build())
                .setEphemeral(true)
                .queue();
        }
    }

    public void onTrackEnd(TrackEndEvent event) {
        GuildMusicManager manager = musicManagers.get(event.getGuildId());
        if (manager != null) {
            manager.scheduler().onTrackEnd(event.getEndReason());
        }
    }

    private void play(SlashCommandInteractionEvent event, Guild guild) {
        Member member = event.getMember();
        if (!connectToMemberChannel(event, guild, member)) {
            return;
        }

        event.deferReply(false).queue();
        String query = event.getOption("query").getAsString().trim();
        GuildMusicManager manager = getOrCreateMusicManager(guild.getIdLong());

        CompletableFuture.supplyAsync(() -> normalizeIdentifier(query))
            .thenAccept(identifier -> manager.getOrCreateLink()
                .loadItem(identifier)
                .subscribe(new AudioLoadHandler(event, manager), error -> event.getHook().sendMessageEmbeds(MusicEmbeds.error(
                    "Lavalink baglantisi yok",
                    "Muzik motoruna ulasilamadi. Sunucuda `Lavalink.jar` baslamamis olabilir.\n`" + trim(error.getMessage()) + "`"
                ).build()).queue()))
            .exceptionally(error -> {
                event.getHook().sendMessageEmbeds(MusicEmbeds.error(
                    "Arama hazirlanamadi",
                    "`" + trim(error.getMessage()) + "`"
                ).build()).queue();
                return null;
            });
    }

    private void skip(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager manager = getOrCreateMusicManager(guild.getIdLong());
        TrackScheduler.SkipResult result = manager.scheduler().skip();

        switch (result) {
            case NEXT_TRACK -> event.replyEmbeds(MusicEmbeds.success("Gecildi", "Siradaki parcaya gecildi.").build()).queue();
            case STOPPED -> event.replyEmbeds(MusicEmbeds.success("Sira bitti", "Calan parca durduruldu; sirada baska parca yok.").build()).queue();
            case NOTHING_PLAYING -> event.replyEmbeds(MusicEmbeds.info("Calan parca yok", "Gecilecek aktif parca veya sira yok.").build())
                .setEphemeral(true)
                .queue();
        }
    }

    private void stop(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager manager = getOrCreateMusicManager(guild.getIdLong());
        manager.stop();
        event.replyEmbeds(MusicEmbeds.success("Durduruldu", "Calma durdu ve sira temizlendi.").build()).queue();
    }

    private void pause(SlashCommandInteractionEvent event, Guild guild, boolean paused) {
        lavalink.getOrCreateLink(guild.getIdLong())
            .getPlayer()
            .flatMap(player -> player.setPaused(paused))
            .subscribe(player -> event.replyEmbeds(MusicEmbeds.success(
                paused ? "Duraklatildi" : "Devam ediyor",
                paused ? "Calan parca beklemeye alindi." : "Calma kaldigi yerden devam ediyor."
            ).build()).queue(), error -> event.replyEmbeds(MusicEmbeds.warning(
                "Aktif oynatici yok",
                "Once `/play` ile bir parca baslat."
            ).build()).setEphemeral(true).queue());
    }

    private void nowPlaying(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager manager = getOrCreateMusicManager(guild.getIdLong());
        var player = manager.getOrCreateLink().getCachedPlayer();

        if (player == null || player.getTrack() == null) {
            event.replyEmbeds(MusicEmbeds.info("Calan parca yok", "Su an aktif parca bulunmuyor.").build())
                .setEphemeral(true)
                .queue();
            return;
        }

        var track = player.getTrack();
        var info = track.getInfo();
        QueuedTrack data = track.getUserData(QueuedTrack.class);
        String requester = data == null ? "Bilinmiyor" : "<@" + data.requesterId() + ">";

        String url = info.getUri();
        event.replyEmbeds(new EmbedBuilder()
            .setColor(MusicEmbeds.SPOTIFY_GREEN)
            .setTitle("Simdi caliyor")
            .setDescription(url == null || url.isBlank() ? "**%s**".formatted(info.getTitle()) : "[%s](%s)".formatted(info.getTitle(), url))
            .addField("Sanatci/Kaynak", nullToDash(info.getAuthor()), true)
            .addField("Konum", AudioLoadHandler.formatMillis(player.getPosition()) + " / " + AudioLoadHandler.formatMillis(info.getLength()), true)
            .addField("Istek", requester, true)
            .build()).queue();
    }

    private void queue(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager manager = getOrCreateMusicManager(guild.getIdLong());
        var tracks = manager.scheduler().snapshot(10);

        if (tracks.isEmpty()) {
            event.replyEmbeds(MusicEmbeds.info("Sira bos", "Sirada bekleyen parca yok.").build())
                .setEphemeral(true)
                .queue();
            return;
        }

        StringBuilder description = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            var info = tracks.get(i).getInfo();
            description.append(i + 1)
                .append(". ")
                .append(info.getTitle())
                .append(" - ")
                .append(AudioLoadHandler.formatMillis(info.getLength()))
                .append('\n');
        }

        int remaining = Math.max(0, manager.scheduler().size() - tracks.size());
        if (remaining > 0) {
            description.append("\n+").append(remaining).append(" parca daha");
        }

        event.replyEmbeds(new EmbedBuilder()
            .setColor(MusicEmbeds.SPOTIFY_GREEN)
            .setTitle("Sira")
            .setDescription(description.toString())
            .build()).queue();
    }

    private void volume(SlashCommandInteractionEvent event, Guild guild) {
        int level = Math.toIntExact(event.getOption("level").getAsLong());
        int clamped = Math.max(0, Math.min(1000, level));

        lavalink.getOrCreateLink(guild.getIdLong())
            .createOrUpdatePlayer()
            .setVolume(clamped)
            .subscribe(player -> event.replyEmbeds(MusicEmbeds.success(
                "Ses ayarlandi",
                "Ses seviyesi `" + clamped + "` olarak ayarlandi."
            ).build()).queue());
    }

    private void leave(SlashCommandInteractionEvent event, Guild guild) {
        getOrCreateMusicManager(guild.getIdLong()).stop();
        event.getJDA().getDirectAudioController().disconnect(guild);
        event.replyEmbeds(MusicEmbeds.success("Ayrildim", "Ses kanalindan cikildi ve sira temizlendi.").build()).queue();
    }

    private boolean connectToMemberChannel(SlashCommandInteractionEvent event, Guild guild, Member member) {
        if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()) {
            event.replyEmbeds(MusicEmbeds.warning(
                "Ses kanali gerekli",
                "Once bir ses kanalina gir, sonra `/play` komutunu kullan."
            ).build()).setEphemeral(true).queue();
            return false;
        }

        GuildVoiceState selfState = guild.getSelfMember().getVoiceState();
        if (selfState == null || !selfState.inAudioChannel()) {
            event.getJDA().getDirectAudioController().connect(member.getVoiceState().getChannel());
        }

        getOrCreateMusicManager(guild.getIdLong());
        return true;
    }

    private GuildMusicManager getOrCreateMusicManager(long guildId) {
        return musicManagers.computeIfAbsent(guildId, id -> new GuildMusicManager(id, lavalink, defaultVolume));
    }

    private static String normalizeIdentifier(String query) {
        if (isSpotifyUrl(query)) {
            return "ytsearch:" + spotifySearchQuery(query);
        }

        if (query.startsWith("ytsearch:")
            || query.startsWith("ytmsearch:")
            || query.startsWith("scsearch:")
            || isUri(query)) {
            return query;
        }

        return "ytsearch:" + query;
    }

    private static boolean isSpotifyUrl(String value) {
        return value.startsWith("https://open.spotify.com/")
            || value.startsWith("http://open.spotify.com/")
            || value.startsWith("spotify:");
    }

    private static String spotifySearchQuery(String spotifyUrl) {
        try {
            String encodedUrl = URLEncoder.encode(spotifyUrl, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://open.spotify.com/oembed?url=" + encodedUrl))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Matcher matcher = JSON_TITLE.matcher(response.body());
                if (matcher.find()) {
                    return unescapeJson(matcher.group(1));
                }
            }
        } catch (Exception ignored) {
            // Fall back to the raw URL if Spotify metadata is temporarily unavailable.
        }

        return spotifyUrl;
    }

    private static String unescapeJson(String value) {
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ");
    }

    private static boolean isUri(String value) {
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return "Bilinmeyen hata";
        }

        return value.length() > 250 ? value.substring(0, 247) + "..." : value;
    }
}
