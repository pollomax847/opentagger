package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.Test;

public class CacheClearTest {

    private static Connection db() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = c.createStatement()) {
            for (String t : MetadataCache.TECHNICAL_TABLES) { st.execute("CREATE TABLE " + t + "(k TEXT)"); st.execute("INSERT INTO " + t + " VALUES('a'),('b')"); }
            for (String t : MetadataCache.PERSONAL_TABLES)  { st.execute("CREATE TABLE " + t + "(k TEXT)"); st.execute("INSERT INTO " + t + " VALUES('x')"); }
        }
        return c;
    }

    private static long count(Connection c, String t) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + t)) { rs.next(); return rs.getLong(1); }
    }

    @Test public void technicalOnlyKeepsThePersonalHistory() throws Exception {
        try (Connection c = db()) {
            Map<String, Integer> removed = MetadataCache.clearTables(c, false);
            for (String t : MetadataCache.TECHNICAL_TABLES) { assertEquals(0, count(c, t)); assertEquals(Integer.valueOf(2), removed.get(t)); }
            for (String t : MetadataCache.PERSONAL_TABLES) assertEquals("l'historique doit rester : " + t, 1, count(c, t));
        }
    }

    @Test public void everythingEmptiesAllTablesButKeepsThemExisting() throws Exception {
        try (Connection c = db()) {
            MetadataCache.clearTables(c, true);
            for (String t : MetadataCache.TECHNICAL_TABLES) assertEquals(0, count(c, t));
            for (String t : MetadataCache.PERSONAL_TABLES) assertEquals(0, count(c, t));
            assertTrue("le schéma reste en place", count(c, "scan_cache") == 0);
        }
    }

    @Test public void aMissingTableDoesNotStopTheOthers() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE lookups(k TEXT)"); st.execute("INSERT INTO lookups VALUES('a')");
            Map<String, Integer> removed = MetadataCache.clearTables(c, false);
            assertEquals(Integer.valueOf(1), removed.get("lookups"));
            assertEquals(Integer.valueOf(-1), removed.get("scan_cache"));
        }
    }
}
