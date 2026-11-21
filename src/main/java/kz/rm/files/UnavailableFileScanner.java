package kz.rm.files;

import org.springframework.stereotype.Component;

/** Safe production default until a qualified AV adapter is provided. */
@Component
class UnavailableFileScanner implements FileScanPort {
    @Override public Verdict scan(String privateStagingKey) { return Verdict.ERROR; }
}
