package com.logininc.ereader.model;
import android.net.Uri;

public abstract class Title {
    private int id;
    private String title;
    private int lenChapters;
    private Uri contentUri;

    public Title(int id, String title, int lenChapters, Uri contentUri) {
        this.id = id;
        this.title = title;
        this.lenChapters = lenChapters;
        this.contentUri = contentUri;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getLenChapters() {
        return lenChapters;
    }

    public void setLenChapters(int lenChapters) {
        this.lenChapters = lenChapters;
    }

    public Uri getContentUri() {
        return contentUri;
    }

    public void setContentUri(Uri contentUri) {
        this.contentUri = contentUri;
    }
}
