package kz.rm.autoorder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import kz.rm.sales.SalesAuthenticatedCommandController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Avoids returning internal IAM identities, SQL causes, foreign resource
 * existence or bearer token contents to untrusted HTTP clients.
 * Invalid/expired/missing JWTs are handled before MVC by the resource server.
 */
@RestControllerAdvice(assignableTypes={CommercialAuthenticatedReadsController.class,
        SalesAuthenticatedCommandController.class})
@ConditionalOnProperty(name="rm.security.oidc.enabled",havingValue="true")
public class CommercialReadErrorHandler {
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String,String>> businessConflict(IllegalStateException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code","COMMAND_CONFLICT"));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String,String>> accessDenied(SecurityException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code","ACCESS_DENIED"));
    }

    @ExceptionHandler(org.springframework.dao.InvalidDataAccessApiUsageException.class)
    ResponseEntity<Map<String,String>> translatedNotFound(
            org.springframework.dao.InvalidDataAccessApiUsageException error) {
        // Spring's @Repository exception translator may wrap the same
        // tenant-scoped not-found signal; preserve non-disclosure at HTTP edge.
        boolean missing=error.getMessage()!=null &&
                error.getMessage().contains("Order not found in tenant");
        return ResponseEntity.status(missing?HttpStatus.NOT_FOUND:
                HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code",
                missing?"RESOURCE_NOT_FOUND":"INTERNAL_ERROR"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalidInput(IllegalArgumentException error) {
        boolean missing=error.getMessage()!=null &&
                (error.getMessage().contains("not found in tenant") ||
                 error.getMessage().contains("Offer not found"));
        return ResponseEntity.status(missing?HttpStatus.NOT_FOUND:HttpStatus.BAD_REQUEST)
                .body(Map.of("code",missing?"RESOURCE_NOT_FOUND":"INVALID_REQUEST"));
    }
}
