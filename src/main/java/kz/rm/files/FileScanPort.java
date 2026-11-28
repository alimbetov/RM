package kz.rm.files;

/** Scanner must explicitly return CLEAN. ERROR is always an admission denial. */
interface FileScanPort {
    enum Verdict { CLEAN, INFECTED, ERROR }
    Verdict scan(String privateStagingKey);
    /** Only an authenticated, bounded V1 plaintext stream from FILES. */
    default Verdict scanAuthenticated(java.io.InputStream in,long expectedBytes) { return Verdict.ERROR; }
}
