package com.vergininkolesi.music;

import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.Color;

public final class MusicEmbeds {
    public static final Color SPOTIFY_GREEN = new Color(30, 215, 96);
    public static final Color WARNING = new Color(255, 193, 7);
    public static final Color ERROR = new Color(237, 66, 69);
    public static final Color NEUTRAL = new Color(88, 101, 242);

    private MusicEmbeds() {
    }

    public static EmbedBuilder success(String title, String description) {
        return base(SPOTIFY_GREEN, title, description);
    }

    public static EmbedBuilder info(String title, String description) {
        return base(NEUTRAL, title, description);
    }

    public static EmbedBuilder warning(String title, String description) {
        return base(WARNING, title, description);
    }

    public static EmbedBuilder error(String title, String description) {
        return base(ERROR, title, description);
    }

    private static EmbedBuilder base(Color color, String title, String description) {
        return new EmbedBuilder()
            .setColor(color)
            .setTitle(title)
            .setDescription(description);
    }
}
