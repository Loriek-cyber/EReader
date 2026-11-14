package com.logininc.ereader.model;

import android.net.Uri;

public class Book extends Title{

    public Book(int id, String title, int lenChapters, Uri contentUri) {
        super(id, title, lenChapters, contentUri);
    }
}
