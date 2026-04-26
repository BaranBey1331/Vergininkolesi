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

import java.awt.Color;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MusicListener extends ListenerAdapter {
    private static final Color SPOTIFY_GREEN = new Color(30, 215, 96);

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
            event.reply("Bu bot komutlari sunucu icinde kullanilir.").setEphemeral(true).queue();
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
            default -> event.reply("Bilinmeyen komut.").setEphemeral(true).queue();
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
        String identifier = normalizeIdentifier(query);
        GuildMusicManager manager = getOrCreateMusicManager(guild.getIdLong());

        manager.getOrCreateLink()
            .loadItem(identifier)
            .subscribe(new AudioLoadHandler(event, manager));
    }

    private void skip(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager manager = getOrCreateMusicManager(guild.getIdLong());
        manager.scheduler().skip();
        event.reply("Gecildi.").queue();
    }

    private void stop(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager manager = getOrCreateMusicManager(guild.getIdLong());
        manager.stop();
        event.reply("Durduruldu ve sira temizlendi.").queue();
    }

    private void pause(SlashCommandInteractionEvent event, Guild guild, boolean paused) {
        lavalink.getOrCreateLink(guild.getIdLong())
            .getPlayer()
            .flatMap(player -> player.setPaused(paused))
            .subscribe(player -> event.reply(paused ? "Duraklatildi." : "Devam ediyor.").queue(),
                error -> event.reply("Aktif oynatici bulunamadi.").setEphemeral(true).queue());
    }

    private void nowPlaying(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager manager = getOrCreateMusicManager(guild.getIdLong());
        var player = manager.getOrCreateLink().getCachedPlayer();

        if (player == null || player.getTrack() == null) {
            event.reply("Su an calan parca yok.").setEphemeral(true).queue();
            return;
        }

        var track = player.getTrack();
        var info = track.getInfo();
        QueuedTrack data = track.getUserData(QueuedTrack.class);
        String requester = data == null ? "Bilinmiyor" : "<@" + data.requesterId() + ">";

        event.replyEmbeds(new EmbedBuilder()
            .setColor(SPOTIFY_GREEN)
            .setTitle("Simdi caliyor")
            .setDescription("[%s](%s)".formatted(info.getTitle(), info.getUri()))
            .addField("Sanatci/Kaynak", info.getAuthor(), true)
            .addField("Konum", AudioLoadHandler.formatMillis(player.getPosition()) + " / " + AudioLoadHandler.formatMillis(info.getLength()), true)
            .addField("Istek", requester, true)
            .build()).queue();
    }

    private void queue(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager manager = getOrCreateMusicManager(guild.getIdLong());
        var tracks = manager.scheduler().snapshot(10);

        if (tracks.isEmpty()) {
            event.reply("Sira bos.").setEphemeral(true).queue();
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
            .setColor(SPOTIFY_GREEN)
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
            .subscribe(player -> event.reply("Ses seviyesi `" + clamped + "` olarak ayarlandi.").queue());
    }

    private void leave(SlashCommandInteractionEvent event, Guild guild) {
        getOrCreateMusicManager(guild.getIdLong()).stop();
        event.getJDA().getDirectAudioController().disconnect(guild);
        event.reply("Ses kanalindan ayrildim.").queue();
    }

    private boolean connectToMemberChannel(SlashCommandInteractionEvent event, Guild guild, Member member) {
        if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()) {
            event.reply("Once bir ses kanalina gir.").setEphemeral(true).queue();
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
        if (query.startsWith("ytsearch:")
            || query.startsWith("ytmsearch:")
            || query.startsWith("scsearch:")
            || isUri(query)) {
            return query;
        }

        return "ytsearch:" + query;
    }

    private static boolean isUri(String value) {
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (URISyntaxException ignored) {
            return false;
        }
    }
}
