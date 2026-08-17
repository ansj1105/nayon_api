package com.nayon.api.store.google;

import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class GoogleServiceAccountAccessTokenProvider
        implements GooglePlayAccessTokenProvider {

    private static final String ANDROID_PUBLISHER_SCOPE =
            "https://www.googleapis.com/auth/androidpublisher";

    private final String credentialsFile;
    private GoogleCredentials credentials;

    public GoogleServiceAccountAccessTokenProvider(
            @Value("${nayon.store.google-play.credentials-file:}")
            String credentialsFile) {
        this.credentialsFile = credentialsFile;
    }

    @Override
    public synchronized String accessToken() {
        if (credentialsFile == null || credentialsFile.isBlank()) {
            throw new GooglePlayGatewayException(
                    "GOOGLE_PLAY_NOT_CONFIGURED", false,
                    "Google Play credentials file is not configured.");
        }
        try {
            if (credentials == null) {
                try (InputStream input = Files.newInputStream(Path.of(credentialsFile))) {
                    credentials = GoogleCredentials.fromStream(input)
                            .createScoped(List.of(ANDROID_PUBLISHER_SCOPE));
                }
            }
            credentials.refreshIfExpired();
            if (credentials.getAccessToken() == null) {
                credentials.refresh();
            }
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException exception) {
            throw new GooglePlayGatewayException(
                    "GOOGLE_PLAY_UNAVAILABLE", true,
                    "Google Play credentials could not provide an access token.",
                    exception);
        }
    }
}
