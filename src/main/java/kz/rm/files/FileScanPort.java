package kz.rm.files;

/** Scanner must explicitly return CLEAN. ERROR is always an admission denial. */
interface FileScanPort {
    enum Verdict { CLEAN, INFECTED, ERROR }
    Verdict scan(String privateStagingKey);
}
