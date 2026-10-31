package io.github.rohan9405.ambient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.Test;

public class NewsTest {
    private static String story(String url, String title, String source, String time) {
        String payload = Base64.getEncoder().encodeToString(("[null,null,\"" + url + "\"]").getBytes(StandardCharsets.UTF_8));
        return "<a class=\"WwrzSb\" jslog=\"95014; 5:" + payload + "; track:click,vis\" href=\"./read/CBMi1\"></a>"
                + "<div class=\"vr1PYe\" data-n-tid=\"9\">" + source + "</div>"
                + "<a class=\"JtKRv\" href=\"./read/CBMi1?hl=en-IN\" aria-label=\"x\">" + title + "</a>"
                + "<time class=\"hvbAAd\" datetime=\"" + time + "\">3 hours ago</time>";
    }

    @Test
    public void googleNewsStoriesCarryTheArticleTitlePublisherAndTime() {
        String page = "<html>" + story("https://www.thehindu.com/sci-tech/gslv.ece", "GSLV places NVS-03 into orbit &amp; more",
                "The Hindu", "2026-10-30T19:09:21Z")
                + story("https://www.bbc.com/news/articles/ck9", "Why hackers are the prime suspect", "BBC", "2026-10-31T02:12:19Z")
                + story("https://www.thehindu.com/sci-tech/gslv.ece", "The same story again", "The Hindu", "2026-10-30T19:09:21Z")
                + "</html>";
        List<News.Story> s = News.parseTopicPage(page, News.INDIA);
        assertEquals(2, s.size());   // each article once
        assertEquals("GSLV places NVS-03 into orbit & more", s.get(0).title);
        assertEquals("The Hindu", s.get(0).source);
        assertEquals("https://www.thehindu.com/sci-tech/gslv.ece", s.get(0).link);
        assertEquals(News.GOOGLE, s.get(0).from);
        assertEquals(1793387361000L, s.get(0).published);
    }

    @Test
    public void theNewsApiStoriesGetThePublishersName() {
        String json = "{\"meta\":{},\"data\":[{\"title\":\"GAIL Q2 profit surges\",\"url\":\"https://economictimes.indiatimes.com/a\","
                + "\"image_url\":\"https://img.etimg.com/thumb/1.jpg\",\"source\":\"economictimes.indiatimes.com\","
                + "\"published_at\":\"2026-10-31T04:10:48.000000Z\"},{\"title\":\"\",\"url\":\"https://x.com\"}]}";
        List<News.Story> s = News.parseTheNews(json, News.BUSINESS);
        assertEquals(1, s.size());
        assertEquals("The Economic Times", s.get(0).source);
        assertEquals("https://img.etimg.com/thumb/1.jpg", s.get(0).image);
        assertEquals(News.THENEWS, s.get(0).from);
        assertTrue(s.get(0).published > 0);
        assertTrue(News.parseTheNews("<html>error</html>", News.BUSINESS).isEmpty());
    }

    @Test
    public void publisherNamesFromTheListElseTheWebsite() {
        assertEquals("The Times of India", News.publisher("timesofindia.indiatimes.com"));
        assertEquals("MediaNama", News.publisher("https://www.medianama.com/2026/10/x/"));
        assertEquals("thequint.com", News.publisher("www.thequint.com"));   // not on the list: the website
    }

    @Test
    public void topicsMapToBothSources() {
        assertTrue(News.topicPage(News.INDIA).contains("/topic/NATION?"));
        assertTrue(News.topicPage(News.SPORTS).contains("/topic/SPORTS?"));
        assertTrue(News.theNewsUrl(News.INDIA, "t").contains("locale=in&") && News.theNewsUrl(News.INDIA, "t").contains("categories=general"));
        assertTrue(!News.theNewsUrl(News.WORLD, "t").contains("locale=") && News.theNewsUrl(News.WORLD, "t").contains("categories=general"));
        assertTrue(News.theNewsUrl(News.HEALTH, "t").contains("categories=health"));
        assertEquals(java.util.Set.of(News.INDIA, News.BUSINESS, News.TECH), News.ON_AT_FIRST);
    }

    @Test
    public void mainImageFromTheArticlePage() {
        String page = "<head><meta content=\"https://techcrunch.com/wp-content/uploads/2026/10/a.jpg?w=799\" property=\"og:image\"></head>";
        assertEquals("https://techcrunch.com/wp-content/uploads/2026/10/a.jpg", News.mainImage(page));
        assertEquals("", News.mainImage("<head></head>"));
    }
}
