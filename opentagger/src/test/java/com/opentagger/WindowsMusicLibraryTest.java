package com.opentagger;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Bibliothèque « Musique » de Windows lue depuis son fichier .library-ms (2026-10-13). */
public class WindowsMusicLibraryTest {

    private static final String XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <libraryDescription xmlns="http://schemas.microsoft.com/windows/2009/library">
          <name>@shell32.dll,-34584</name>
          <propertyStore><property name="HasModifiedLocations" type="boolean"><![CDATA[true]]></property></propertyStore>
          <templateInfo><folderType>{94d6ddcc-4a68-4175-a374-bd584a510b78}</folderType></templateInfo>
          <searchConnectorDescriptionList>
            <searchConnectorDescription publisher="Microsoft" product="Windows">
              <isDefaultSaveLocation>true</isDefaultSaveLocation>
              <simpleLocation><url>knownfolder:{4BD8D571-6D19-48D3-BE97-422220080E43}</url><serialized>MBAAAEAF</serialized></simpleLocation>
            </searchConnectorDescription>
            <searchConnectorDescription>
              <simpleLocation><url>D:\\Musique &amp; Clips</url><serialized>MBAAAEAF</serialized></simpleLocation>
            </searchConnectorDescription>
            <searchConnectorDescription>
              <simpleLocation><url>\\\\NAS\\musique</url></simpleLocation>
            </searchConnectorDescription>
          </searchConnectorDescriptionList>
        </libraryDescription>
        """;

    @Test public void locationsAndKnownFoldersAreRead() {
        String music = WindowsMusicLibrary.knownGuidsForTest().get("music");
        List<String> got = WindowsMusicLibrary.parse(XML, guid -> guid.equals(music) ? "C:\\Users\\paul\\Music" : null);
        assertEquals(List.of("C:\\Users\\paul\\Music", "D:\\Musique & Clips", "\\\\NAS\\musique"), got);
    }

    @Test public void environmentVariablesAreExpanded() {
        assertEquals("C:\\Users\\paul\\Music", WindowsMusicLibrary.expandEnv("%USERPROFILE%\\Music",
                Map.of("USERPROFILE", "C:\\Users\\paul")::get));
        assertEquals("%INCONNU%\\x", WindowsMusicLibrary.expandEnv("%INCONNU%\\x", Map.<String, String>of()::get));
    }

    @Test public void nestedAndRepeatedFoldersAreScannedOnce() {
        assertEquals(List.of("/m/Music", "/x"),
                Config.withoutNested(List.of("/m/Music", "/m/Music/Rock", "/x", "/m/Music"), false));
        assertEquals(List.of("/m"), Config.withoutNested(List.of("/m/Music/Rock", "/m"), false));
    }
}
