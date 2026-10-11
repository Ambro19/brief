package net.onetechly.brief.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Lets browsers keep the web app's files (2.3).
 *
 * Spring Security marks every response "do not store", which is right for the API: an answer
 * about posts or accounts must never come out of a cache. It was also applied to the web app's
 * JavaScript, style sheet and fonts, so each visit downloaded them again.
 *
 * The files under /assets are different. The build tool (Vite) puts a fingerprint of the
 * content into every file name, for example index-BZ0PIfOj.js. A changed file gets a new name,
 * so a file with a given name never changes and can be kept for a year ("immutable").
 *
 * index.html is NOT under /assets and keeps "do not store". It is small, it is fetched on
 * every visit, and it names the current asset files. That is how a new release reaches people.
 *
 * How this fits with Spring Security: its cache headers are added just before the response is
 * sent, and only if the response has no Cache-Control header yet. The handler below sets one,
 * so Spring Security leaves these responses alone.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** One year, the longest time the HTTP specification suggests. */
    static final Duration ASSET_LIFETIME = Duration.ofDays(365);

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // This is added beside Spring Boot's own handler for everything else ("/**"); the more
        // specific pattern wins for requests that start with /assets/.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/public/assets/") // the trailing slash is required
                .setCacheControl(CacheControl.maxAge(ASSET_LIFETIME).cachePublic().immutable());
    }
}
