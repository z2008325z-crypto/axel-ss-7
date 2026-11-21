package com.example.myapp;

import android.net.Uri;

public class Song {
    public final long id;
    public String title;
    public final String artist;
    public final long duration;
    public final Uri uri;

    // أغاني الإنترنت (أونلاين)
    public boolean online = false;
    public String artUrl;
    public String source = "";

    public Song(long id, String title, String artist, long duration, Uri uri) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.duration = duration;
        this.uri = uri;
    }

    public Song(long id, String title, String artist, long duration, Uri uri, String artUrl, String source) {
        this(id, title, artist, duration, uri);
        this.online = true;
        this.artUrl = artUrl;
        this.source = source == null ? "" : source;
    }
}
