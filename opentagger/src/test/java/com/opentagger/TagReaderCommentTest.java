package com.opentagger;

import static org.junit.Assert.assertEquals;

import org.jaudiotagger.tag.id3.ID3v23Frame;
import org.jaudiotagger.tag.id3.ID3v23Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM;
import org.junit.Test;

public class TagReaderCommentTest {

    private static ID3v23Frame comm(String desc, String text) {
        FrameBodyCOMM b = new FrameBodyCOMM();
        b.setDescription(desc);
        b.setText(text);
        ID3v23Frame f = new ID3v23Frame("COMM");
        f.setBody(b);
        return f;
    }

    @Test
    public void iTunesTechnicalFramesAreNotTheComment() throws Exception {
        ID3v23Tag tag = new ID3v23Tag();
        tag.addField(comm("iTunSMPB", " 00000000 00000210 0000093C"));
        tag.addField(comm("iTunNORM", " 00000AF7 00000B2B"));
        tag.addField(comm("", "vrai commentaire"));
        assertEquals("vrai commentaire", TagReader.comment(tag));
    }

    @Test
    public void onlyTechnicalFramesMeansNoComment() throws Exception {
        ID3v23Tag tag = new ID3v23Tag();
        tag.addField(comm("iTunNORM", " 00000AF7 00000B2B"));
        assertEquals("", TagReader.comment(tag));
    }

    @Test
    public void plainCommentIsReturned() throws Exception {
        ID3v23Tag tag = new ID3v23Tag();
        tag.addField(comm("", "salut"));
        assertEquals("salut", TagReader.comment(tag));
    }
}
