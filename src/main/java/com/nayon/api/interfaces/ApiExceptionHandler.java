package com.nayon.api.interfaces;

import com.nayon.api.auth.InvalidIdentityClaimException;
import com.nayon.api.accountlink.AccountLinkRewardException;
import com.nayon.api.battle.BattleConflictException;
import com.nayon.api.battle.BattleEconomyNotBootstrappedException;
import com.nayon.api.battle.BattleNotFoundException;
import com.nayon.api.battle.offline.OfflineBattleConflictException;
import com.nayon.api.economy.EconomyBootstrapConflictException;
import com.nayon.api.gacha.EconomyNotBootstrappedException;
import com.nayon.api.gacha.GachaConflictException;
import com.nayon.api.gacha.InsufficientAssetException;
import com.nayon.api.korion.KorionWalletLinkException;
import com.nayon.api.legal.LegalDocumentNotFoundException;
import com.nayon.api.limitedbenefit.LimitedBenefitException;
import com.nayon.api.limitedbenefit.admob.AdMobSsvVerificationException;
import com.nayon.api.save.IdempotencyConflictException;
import com.nayon.api.save.SaveNotFoundException;
import com.nayon.api.save.SaveRevisionConflictException;
import com.nayon.api.share.EconomyNotBootstrappedForShareException;
import com.nayon.api.share.ShareRequiredException;
import com.nayon.api.store.StoreConfigurationException;
import com.nayon.api.store.StorePurchaseException;
import com.nayon.api.subscription.SubscriptionException;
import com.nayon.api.subscription.rtdn.GooglePlayRtdnException;
import com.nayon.api.levelreward.LevelRewardException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.validation.ConstraintViolationException;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AdMobSsvVerificationException.class)
    ResponseEntity<ApiError> adMobVerification(AdMobSsvVerificationException exception) {
        return error(HttpStatus.BAD_REQUEST,
                "ADMOB_SSV_INVALID", exception.getMessage());
    }

    @ExceptionHandler(LimitedBenefitException.class)
    ResponseEntity<ApiError> limitedBenefit(LimitedBenefitException exception) {
        HttpStatus status = switch (exception.code()) {
            case "LIMITED_BENEFIT_OFFER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "LIMITED_BENEFIT_PROOF_REQUIRED", "LIMITED_BENEFIT_PROOF_INVALID" ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case "LIMITED_BENEFIT_PROVIDER_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return error(status, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(StoreConfigurationException.class)
    ResponseEntity<ApiError> storeConfiguration(StoreConfigurationException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "STORE_NOT_CONFIGURED", exception.getMessage());
    }

    @ExceptionHandler(StorePurchaseException.class)
    ResponseEntity<ApiError> storePurchase(StorePurchaseException exception) {
        HttpStatus status = switch (exception.code()) {
            case "STORE_PRODUCT_NOT_FOUND", "STORE_PURCHASE_NOT_FOUND",
                 "GOOGLE_PLAY_PURCHASE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "STORE_IDEMPOTENCY_CONFLICT", "STORE_PURCHASE_TOKEN_CONFLICT",
                 "ECONOMY_NOT_BOOTSTRAPPED" -> HttpStatus.CONFLICT;
            case "GOOGLE_PLAY_PURCHASE_PENDING", "GOOGLE_PLAY_PURCHASE_CANCELLED",
                 "GOOGLE_PLAY_PRODUCT_MISMATCH", "GOOGLE_PLAY_ACCOUNT_MISMATCH" ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case "GOOGLE_PLAY_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return error(status, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(SubscriptionException.class)
    ResponseEntity<ApiError> subscription(SubscriptionException exception) {
        HttpStatus status = switch (exception.code()) {
            case "SUBSCRIPTION_PRODUCT_NOT_FOUND", "SUBSCRIPTION_NOT_FOUND",
                 "SUBSCRIPTION_VERIFICATION_NOT_FOUND",
                 "GOOGLE_PLAY_SUBSCRIPTION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SUBSCRIPTION_IDEMPOTENCY_CONFLICT",
                 "SUBSCRIPTION_PURCHASE_TOKEN_CONFLICT",
                 "SUBSCRIPTION_VERIFICATION_IN_PROGRESS",
                 "SUBSCRIPTION_REWARD_IDEMPOTENCY_CONFLICT",
                 "ECONOMY_NOT_BOOTSTRAPPED" -> HttpStatus.CONFLICT;
            case "GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_MISMATCH",
                 "GOOGLE_PLAY_SUBSCRIPTION_ACCOUNT_MISMATCH",
                 "SUBSCRIPTION_REQUIRED" ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case "GOOGLE_PLAY_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return error(status, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(GooglePlayRtdnException.class)
    ResponseEntity<ApiError> googlePlayRtdn(GooglePlayRtdnException exception) {
        HttpStatus status = switch (exception.code()) {
            case "GOOGLE_PLAY_RTDN_UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
            case "GOOGLE_PLAY_RTDN_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "GOOGLE_PLAY_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "GOOGLE_PLAY_RTDN_INVALID",
                 "GOOGLE_PLAY_RTDN_PACKAGE_MISMATCH" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return error(status, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(LevelRewardException.class)
    ResponseEntity<ApiError> levelReward(LevelRewardException exception) {
        HttpStatus status = switch (exception.code()) {
            case "LEVEL_REWARD_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "LEVEL_REWARD_IDEMPOTENCY_CONFLICT",
                 "ECONOMY_NOT_BOOTSTRAPPED" -> HttpStatus.CONFLICT;
            case "LEVEL_REWARD_LEVEL_REQUIRED",
                 "LEVEL_REWARD_SUBSCRIPTION_REQUIRED" ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.CONFLICT;
        };
        return error(status, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(LegalDocumentNotFoundException.class)
    ResponseEntity<ApiError> legalDocumentNotFound(LegalDocumentNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "LEGAL_DOCUMENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(AccountLinkRewardException.class)
    ResponseEntity<ApiError> accountLinkReward(AccountLinkRewardException exception) {
        return error(HttpStatus.CONFLICT, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(KorionWalletLinkException.class)
    ResponseEntity<ApiError> korionWalletLink(KorionWalletLinkException exception) {
        HttpStatus status = switch (exception.code()) {
            case "KORION_WALLET_LINK_NOT_FOUND", "KORION_WALLET_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "KORION_WALLET_LINK_RATE_LIMITED", "KORION_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "KORION_GATEWAY_FAILED", "KORION_GATEWAY_NOT_CONFIGURED",
                 "KORION_GATEWAY_INVALID_RESPONSE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return error(status, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(SaveRevisionConflictException.class)
    ResponseEntity<ApiError> saveConflict(SaveRevisionConflictException exception) {
        return error(HttpStatus.CONFLICT, "SAVE_REVISION_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict(IdempotencyConflictException exception) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage());
    }

    @ExceptionHandler(EconomyBootstrapConflictException.class)
    ResponseEntity<ApiError> economyBootstrapConflict(
            EconomyBootstrapConflictException exception) {
        return error(
                HttpStatus.CONFLICT,
                "ECONOMY_ALREADY_BOOTSTRAPPED",
                exception.getMessage());
    }

    @ExceptionHandler(GachaConflictException.class)
    ResponseEntity<ApiError> gachaConflict(GachaConflictException exception) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage());
    }

    @ExceptionHandler(BattleConflictException.class)
    ResponseEntity<ApiError> battleConflict(BattleConflictException exception) {
        return error(HttpStatus.CONFLICT, "BATTLE_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(OfflineBattleConflictException.class)
    ResponseEntity<ApiError> offlineBattleConflict(
            OfflineBattleConflictException exception) {
        return error(HttpStatus.CONFLICT, "OFFLINE_BATTLE_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(BattleEconomyNotBootstrappedException.class)
    ResponseEntity<ApiError> battleEconomyNotBootstrapped(
            BattleEconomyNotBootstrappedException exception) {
        return error(HttpStatus.CONFLICT, "ECONOMY_NOT_BOOTSTRAPPED", exception.getMessage());
    }

    @ExceptionHandler(BattleNotFoundException.class)
    ResponseEntity<ApiError> battleNotFound(BattleNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "BATTLE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(InsufficientAssetException.class)
    ResponseEntity<ApiError> insufficientAsset(InsufficientAssetException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_ASSET", exception.getMessage());
    }

    @ExceptionHandler(EconomyNotBootstrappedException.class)
    ResponseEntity<ApiError> economyNotBootstrapped(
            EconomyNotBootstrappedException exception) {
        return error(HttpStatus.CONFLICT, "ECONOMY_NOT_BOOTSTRAPPED", exception.getMessage());
    }

    @ExceptionHandler(EconomyNotBootstrappedForShareException.class)
    ResponseEntity<ApiError> shareEconomyNotBootstrapped(
            EconomyNotBootstrappedForShareException exception) {
        return error(HttpStatus.CONFLICT, "ECONOMY_NOT_BOOTSTRAPPED", exception.getMessage());
    }

    @ExceptionHandler(ShareRequiredException.class)
    ResponseEntity<ApiError> shareRequired(ShareRequiredException exception) {
        return error(HttpStatus.CONFLICT, "SHARE_REQUIRED", exception.getMessage());
    }

    @ExceptionHandler(SaveNotFoundException.class)
    ResponseEntity<ApiError> saveNotFound(SaveNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "SAVE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(InvalidIdentityClaimException.class)
    ResponseEntity<ApiError> invalidIdentity(InvalidIdentityClaimException exception) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_IDENTITY", exception.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status, String code, String message) {
        ApiError body = new ApiError(
                code,
                message,
                UUID.randomUUID().toString(),
                Map.of());
        return ResponseEntity.status(status).body(body);
    }
}
