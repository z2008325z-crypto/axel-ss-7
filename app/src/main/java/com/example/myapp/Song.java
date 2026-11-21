package com.example.myapp;

import android.net.Uri;

public class Song {
    public final long id;
    public String title;
    public final String artist;
    public final long duration;
    public final Uri uri;

    public Song(long id, String title, String artist, long duration, Uri uri) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.duration = duration;
        this.uri = uri;
    }
}
