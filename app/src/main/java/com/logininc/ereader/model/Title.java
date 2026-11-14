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
}
