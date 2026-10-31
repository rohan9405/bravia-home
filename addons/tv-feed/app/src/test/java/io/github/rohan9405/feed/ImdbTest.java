package io.github.rohan9405.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Map;

import org.junit.Test;

public class ImdbTest {
    @Test
    public void ratingsByIdAndNothingForTitlesWithoutVotes() throws Exception {
        Map<String, String> r = Imdb.parse("{\"data\":{\"titles\":[{\"id\":\"tt45854934\",\"ratingsSummary\":{\"aggregateRating\":6.7}},"
                + "{\"id\":\"tt1\",\"ratingsSummary\":{\"aggregateRating\":null}},{\"id\":\"tt2\",\"ratingsSummary\":{\"aggregateRating\":8}}]}}");
        assertEquals("6.7", r.get("tt45854934"));
        assertEquals("8.0", r.get("tt2"));
        assertFalse(r.containsKey("tt1"));
        assertEquals(0, Imdb.parse("{\"errors\":[]}").size());
    }
}
