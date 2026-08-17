package com.nayon.api.interfaces;

import com.nayon.api.subscription.rtdn.GooglePlayRtdnPushRequest;
import com.nayon.api.subscription.rtdn.GooglePlayRtdnService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/google-play/rtdn")
public class GooglePlayRtdnController {

    private final GooglePlayRtdnService service;

    public GooglePlayRtdnController(GooglePlayRtdnService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "Authorization", required = false)
            String authorization,
            @Valid @RequestBody GooglePlayRtdnPushRequest request) {
        service.receive(authorization, request);
        return ResponseEntity.noContent().build();
    }
}
