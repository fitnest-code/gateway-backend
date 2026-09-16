package az.fitnest.gateway.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fitnest.landing")
public class LandingKeyConfig {

    /**
     * Shared secret expected in {@code X-FitNest-Landing-Key}.
     * Empty means the check is disabled (local/dev until the secret is set).
     */
    private String apiKey = "";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isEnforced() {
        return !apiKey.isEmpty();
    }
}
