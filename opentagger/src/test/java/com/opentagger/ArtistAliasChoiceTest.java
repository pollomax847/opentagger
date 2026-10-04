package com.opentagger;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

/** Données reprises de l'API MusicBrainz réelle (2026-10-04) pour 宇多田ヒカル et 볼빨간사춘기. */
public class ArtistAliasChoiceTest {

    private static JsonNode json(String s) throws Exception { return new ObjectMapper().readTree(s); }

    private static final String UTADA = "["
        + "{\"name\":\"Cubic U\",\"locale\":\"en\",\"type\":\"Artist name\",\"primary\":false},"
        + "{\"name\":\"Cubic U (Utada Hikaru)\",\"locale\":null,\"type\":\"Search hint\",\"primary\":null},"
        + "{\"name\":\"Hikaru Utada\",\"locale\":\"en\",\"type\":\"Artist name\",\"primary\":true},"
        + "{\"name\":\"Hikki\",\"locale\":\"en\",\"type\":\"Artist name\",\"primary\":false},"
        + "{\"name\":\"우타다히카루\",\"locale\":\"ko\",\"type\":\"Artist name\",\"primary\":false}]";

    private static final String BOL4 = "["
        + "{\"name\":\"Bolbbalgan4\",\"locale\":\"en\",\"type\":\"Artist name\",\"primary\":false},"
        + "{\"name\":\"BOL4\",\"locale\":\"en\",\"type\":\"Artist name\",\"primary\":true},"
        + "{\"name\":\"볼빨간사춘기\",\"locale\":\"ko\",\"type\":\"Artist name\",\"primary\":true}]";

    @Test
    public void primaryAliasWinsOverTheFirstOne() throws Exception {
        assertEquals("Hikaru Utada", MusicBrainzClient.findAliasByLocale(json(UTADA), "en"));
        assertEquals("BOL4", MusicBrainzClient.findAliasByLocale(json(BOL4), "en"));
    }

    @Test
    public void neverReturnsANonLatinAlias() throws Exception {
        // locale « ko » : le seul alias coréen est en hangul → rien, au lieu de remplacer du hangul par du hangul
        assertEquals("", MusicBrainzClient.findAliasByLocale(json(UTADA), "ko"));
    }

    @Test
    public void unknownLocaleReturnsEmptySoTheCallerCanFallBackToEnglish() throws Exception {
        assertEquals("", MusicBrainzClient.findAliasByLocale(json(UTADA), "fr"));
    }
}
