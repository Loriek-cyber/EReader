package com.logininc.ereader.converter;

import org.junit.Test;

import java.io.InputStream;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EbookTest {
    InputStream is = Objects.requireNonNull(getClass().getClassLoader()).getResourceAsStream("claude.epub");

    @Test
    public void assertItLoads() {
        assertNotNull(is);
    }

    @Test
    public void assertItTheParserWorks(){

    }
    


}
