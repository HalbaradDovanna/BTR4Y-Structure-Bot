package com.evebot.structuremonitor.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * Handles the EVE SSO OAuth2 "Authorization Code" flow.
 *
 * Flow:
 *  1. Director visits /auth/login → redirected to EVE SSO
 *  2. They log in with their EVE character
 *  3. EVE redirects them back to /auth/callback?code=XYZ
 *  4. We exchange the code for tokens, display the refresh token + char ID
 */
@Controller
public class EveAuthController {

    private static final Logger log = LoggerFactory.getLogger(EveAuthController.class);

    private static final String EVE_TOKEN_URL = "https://login.eveonline.com/v2/oauth/token";
    private static final String EVE_AUTH_URL  = "https://login.eveonline.com/v2/oauth/authorize";
    private static final String EVE_VERIFY_URL = "https://login.eveonline.com/oauth/verify";

    private static final String REQUIRED_SCOPES =
            "esi-corporations.read_structures.v1 " +
            "esi-characters.read_notifications.v1 " +
            "esi-universe.read_structures.v1";

    @Value("${eve.esi.client.id}")
    private String clientId;

    @Value("${eve.esi.client.secret}")
    private String clientSecret;

    // This must match exactly what you set in your EVE developer app's callback URL
    // On Railway it will be: https://your-app.railway.app/auth/callback
    @Value("${eve.esi.callback.url}")
    private String callbackUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Landing page — shows a "Login with EVE" button.
     * Visit: https://your-app.railway.app/auth
     */
    @GetMapping("/auth")
    public String authPage(Model model) {
        model.addAttribute("clientId", clientId);
        model.addAttribute("callbackUrl", callbackUrl);
        model.addAttribute("scopes", REQUIRED_SCOPES);
        return "auth-login"; // renders auth-login.html
    }

    /**
     * Redirects the user to the EVE SSO login page.
     * Visit: https://your-app.railway.app/auth/login
     */
    @GetMapping("/auth/login")
    public String startLogin() {
        String scopesEncoded = REQUIRED_SCOPES.replace(" ", "%20");
        String eveLoginUrl = EVE_AUTH_URL +
                "?response_type=code" +
                "&redirect_uri=" + callbackUrl.replace(":", "%3A").replace("/", "%2F") +
                "&client_id=" + clientId +
                "&scope=" + scopesEncoded +
                "&state=structure-monitor";

        return "redirect:" + eveLoginUrl;
    }

    /**
     * EVE redirects here after the director logs in.
     * We exchange the code for tokens and display the result.
     */
    @GetMapping("/auth/callback")
    public String handleCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "error", required = false) String error,
            Model model
    ) {
        if (error != null) {
            model.addAttribute("error", "EVE SSO returned an error: " + error);
            return "auth-result";
        }

        if (code == null || code.isEmpty()) {
            model.addAttribute("error", "No authorization code received from EVE SSO.");
            return "auth-result";
        }

        try {
            // Step 1: Exchange the authorization code for tokens
            String credentials = clientId + ":" + clientSecret;
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

            String requestBody = "grant_type=authorization_code" +
                    "&code=" + code +
                    "&redirect_uri=" + java.net.URLEncoder.encode(callbackUrl, "UTF-8");

            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(URI.create(EVE_TOKEN_URL))
                    .header("Authorization", basicAuth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> tokenResponse = httpClient.send(tokenRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (tokenResponse.statusCode() != 200) {
                log.error("Token exchange failed: {} - {}", tokenResponse.statusCode(), tokenResponse.body());
                model.addAttribute("error", "Failed to exchange code for token. Status: " + tokenResponse.statusCode());
                return "auth-result";
            }

            JsonNode tokenJson = objectMapper.readTree(tokenResponse.body());
            String accessToken  = tokenJson.get("access_token").asText();
            String refreshToken = tokenJson.get("refresh_token").asText();

            // Step 2: Use the access token to get the character info
            HttpRequest verifyRequest = HttpRequest.newBuilder()
                    .uri(URI.create(EVE_VERIFY_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> verifyResponse = httpClient.send(verifyRequest,
                    HttpResponse.BodyHandlers.ofString());

            String characterName = "Unknown";
            String characterId   = "Unknown";
            String corpName      = "";

            if (verifyResponse.statusCode() == 200) {
                JsonNode verifyJson = objectMapper.readTree(verifyResponse.body());
                characterName = verifyJson.has("CharacterName") ?
                        verifyJson.get("CharacterName").asText() : "Unknown";
                characterId = verifyJson.has("CharacterID") ?
                        String.valueOf(verifyJson.get("CharacterID").asLong()) : "Unknown";
            }

            // Pass all the info to the template
            model.addAttribute("success", true);
            model.addAttribute("characterName", characterName);
            model.addAttribute("characterId", characterId);
            model.addAttribute("refreshToken", refreshToken);

            log.info("Successfully obtained refresh token for character: {} ({})", characterName, characterId);

        } catch (Exception e) {
            log.error("Error during OAuth callback: {}", e.getMessage(), e);
            model.addAttribute("error", "An unexpected error occurred: " + e.getMessage());
        }

        return "auth-result";
    }
}
