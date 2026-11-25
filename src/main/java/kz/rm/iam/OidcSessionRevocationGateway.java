package kz.rm.iam;

/**
 * Trusted provider boundary. A normal return means the remote provider
 * explicitly acknowledged logout; failures MUST throw. Repeated logout
 * requests must be safe (at-least-once delivery, not exactly-once network).
 */
@FunctionalInterface
public interface OidcSessionRevocationGateway {
    void revokeSessions(String providerSubject);
}
