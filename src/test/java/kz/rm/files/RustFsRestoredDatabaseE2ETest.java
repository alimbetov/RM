package kz.rm.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

/**
 * Runs against the restored PostgreSQL database and a freshly restored RustFS
 * volume. Verifies that restored metadata still resolves to original blob bytes.
 */
@SpringBootTest(properties={
    "rm.files.rustfs.enabled=true",
    "rm.files.rustfs.endpoint=${RM_RUSTFS_E2E_ENDPOINT:http://127.0.0.1:9000}",
    "rm.files.rustfs.bucket=${RM_RUSTFS_E2E_BUCKET:rm-files-e2e}",
    "rm.files.rustfs.access-key=${RM_RUSTFS_E2E_ACCESS:rm-rustfs-ci}",
    "rm.files.rustfs.secret-key=${RM_RUSTFS_E2E_SECRET:rm-rustfs-ci-only-secret}",
    "rm.files.rustfs.region=us-east-1"
})
@EnabledIfEnvironmentVariable(named="RM_RUSTFS_RESTORE_E2E",matches="true")
class RustFsRestoredDatabaseE2ETest {
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStoragePort storage;

    @Test void restoredPostgresActivePointerMatchesRestoredRustFsBytes() throws Exception {
        var snapshot=jdbc.query("""
                SELECT s.storage_key,f.size_bytes,f.sha256
                  FROM crm.file_storage s
                  JOIN crm.file_object f ON f.tenant_id=s.tenant_id AND f.id=s.file_id
                 WHERE s.provider='RUSTFS' AND s.state='ACTIVE'
                   AND f.state='AVAILABLE' AND f.scan_status='CLEAN'
                 ORDER BY f.created_at LIMIT 1
                """,rs->rs.next()?new Pointer(rs.getString(1),rs.getLong(2),
                        rs.getString(3)):null);
        assertThat(snapshot).as("Restored PostgreSQL must contain a real approved RustFS file")
                .isNotNull();
        assertThat(storage.matches(snapshot.key(),snapshot.length(),snapshot.sha()))
                .as("Restored metadata SHA must match restored S3 object bytes").isTrue();
    }
    private record Pointer(String key,long length,String sha){}
}
