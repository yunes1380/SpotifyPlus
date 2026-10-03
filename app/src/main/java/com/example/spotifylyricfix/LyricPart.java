package com.example.spotifylyricfix;

public class LyricPart {
    public final long startMs;
    public final long endMs;
    public final String text;

    public LyricPart(long startMs, long endMs, String text) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
    }
}
