package io.github.rohan9405.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Answers in the StreamRatings shared cache's format (GET /v1/rating). */
public class SharedCacheTest {
    @Test
    public void hitKeepsTheCachesTimestamp() throws Exception {
        Omdb.Entry e = Omdb.parseShared(200, "{\"data\":{\"found\":true,\"title\":\"Panchayat\",\"year\":\"2020–\","
                + "\"released\":\"05 Mar 2020\",\"imdbRating\":\"9.0\"},\"ts\":1793285736264,\"cache\":\"hit\"}");
        assertTrue(e.found);
        assertEquals("9.0", e.rating);
        assertEquals("2020–", e.year);
        assertEquals("05 Mar 2020", e.released);
        assertEquals(1793285736264L, e.savedMs);
    }

    @Test
    public void notFoundAndMissingRating() throws Exception {
        Omdb.Entry miss = Omdb.parseShared(200, "{\"data\":{\"found\":false,\"year\":null,\"imdbRating\":null},\"ts\":5}");
        assertFalse(miss.found);
        assertEquals("", miss.rating);
        assertEquals("", miss.year);
        Omdb.Entry na = Omdb.parseShared(200, "{\"data\":{\"found\":true,\"year\":\"2026\",\"imdbRating\":\"N/A\"},\"ts\":5}");
        assertEquals("", na.rating);
    }

    @Test
    public void refusedKeyIsNotTheCacheBeingDown() {
        try {
            Omdb.parseShared(401, "{\"error\":\"Invalid API key!\"}");
            fail();
        } catch (Omdb.SharedCacheDown down) {
            fail("a refused key must not fall back to OMDb");
        } catch (Exception expected) {
            assertEquals("Invalid API key!", expected.getMessage());
        }
    }

    @Test
    public void serverErrorsMeanDown() {
        for (int code : new int[] {500, 502, 404}) {
            try {
                Omdb.parseShared(code, code == 502 ? "<html>bad gateway</html>" : "{}");
                fail();
            } catch (Omdb.SharedCacheDown expected) {
                // Falls back to OMDb.
            } catch (Exception other) {
                fail(code + ": " + other);
            }
        }
    }
}
