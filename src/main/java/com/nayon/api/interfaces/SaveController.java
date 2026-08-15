package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.save.CloudSave;
import com.nayon.api.save.CloudSaveService;
import com.nayon.api.save.SaveNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save")
public class SaveController {

    private final CurrentAccountResolver accountResolver;
    private final CloudSaveService cloudSaveService;

    public SaveController(
            CurrentAccountResolver accountResolver,
            CloudSaveService cloudSaveService) {
        this.accountResolver = accountResolver;
        this.cloudSaveService = cloudSaveService;
    }

    @GetMapping
    public SaveResponse get(@AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return cloudSaveService.get(account.id())
                .map(SaveResponse::from)
                .orElseThrow(SaveNotFoundException::new);
    }

    @PutMapping
    public SaveResponse put(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SaveWriteRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        CloudSave saved = cloudSaveService.put(
                account.id(),
                request.expectedRevision(),
                request.content());
        return SaveResponse.from(saved);
    }

    @PostMapping("/import")
    public ResponseEntity<SaveResponse> importLocal(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @Valid @RequestBody SaveImportRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        boolean replay = cloudSaveService.get(account.id()).isPresent();
        CloudSave saved = cloudSaveService.importInitial(
                account.id(), requestId, request.content());
        SaveResponse response = SaveResponse.from(saved);
        if (replay) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create("/api/v1/save")).body(response);
    }
}
