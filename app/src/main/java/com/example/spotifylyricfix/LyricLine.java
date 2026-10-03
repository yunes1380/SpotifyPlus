package com.example.spotifylyricfix;

import java.util.ArrayList;
import java.util.List;

public class LyricLine {
    public final long startMs;
    public final long endMs;
    public final String text;
    public final List<LyricPart> parts;

    public LyricLine(long startMs, long endMs, String text) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.parts = new ArrayList<LyricPart>();
        this.parts.add(new LyricPart(startMs, endMs, text));
    }

    public LyricLine(long startMs, long endMs, String text, List<LyricPart> parts) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.parts = parts;
    }
}
