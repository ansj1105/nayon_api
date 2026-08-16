package com.nayon.api.interfaces;

import com.nayon.api.auth.InvalidIdentityClaimException;
import com.nayon.api.battle.BattleConflictException;
import com.nayon.api.battle.BattleEconomyNotBootstrappedException;
import com.nayon.api.battle.BattleNotFoundException;
import com.nayon.api.battle.offline.OfflineBattleConflictException;
import com.nayon.api.economy.EconomyBootstrapConflictException;
import com.nayon.api.gacha.EconomyNotBootstrappedException;
import com.nayon.api.gacha.GachaConflictException;
import com.nayon.api.gacha.InsufficientAssetException;
import com.nayon.api.save.IdempotencyConflictException;
import com.nayon.api.save.SaveNotFoundException;
import com.nayon.api.save.SaveRevisionConflictException;
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
