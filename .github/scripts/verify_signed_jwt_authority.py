#!/usr/bin/env python3
"""E.1A: exact-SHA, named-test qualification of the reusable signed JWT authority."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

PACKAGE = "kz.rm.iam."
REQUIRED = {
    "IamSellerAuthenticatedTenantAdmissionQualificationTest": {
        "iamSellerR0RealBearerSubCannotBeOverriddenByActorOrTenantClaims",
        "iamSellerR0SignedIdRefreshWrongPurposeAndBadJwtNeverMintActor",
        "iamSellerR0RoleRevokedAfterSignedTokenAndTenantSelectionDeniesWrite",
        "iamSellerR0MembershipSuspendedAfterBearerBlocksSelectAndMutation",
        "iamSellerR0SuspendedAccountRejectsExistingAndFreshBearer",
        "iamSellerR0TenantSuspendedAfterAccessTokenDeniesCurrentSelectionAndWrite",
    },
    "OidcJwtCryptoQualificationTest": {
        "signatureIssuerAudienceAndExpiryAreAllRequired",
    },
    "R4LocalJwksIntegrationQualificationTest": {
        "localJwksFetchVerifiesSignatureIssuerAudienceExpirySubjectAndRejectsForeignKey",
    },
    "SignedJwtTestAuthorityTest": {
        "publishesOnlyPublicRsaJwkAndDecodesWithProductionValidators",
        "rejectsUntrustedSignatureIssuerAudiencePurposeAndExpiry",
        "independentAuthoritiesCannotVerifyEachOthersTokens",
        "closeIsIdempotentAndNoRemoteJwksIsAllowed",
    },
}
BAD = {"failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(reports, sha, checkout_sha):
    require(bool(re.fullmatch(r"[0-9a-f]{40}", sha)) and sha == checkout_sha,
            "AUTHORITY_SHA_MISMATCH")
    paths = sorted(reports.glob("TEST-*.xml"))
    expected_files = {"TEST-" + PACKAGE + suite + ".xml" for suite in REQUIRED}
    require({p.name for p in paths} == expected_files, "AUTHORITY_SUITE_SET_MISMATCH")
    result = []
    for path in paths:
        data = path.read_bytes()
        root = ET.fromstring(data)
        suite = root.get("name", "")
        require(root.tag == "testsuite" and suite.startswith(PACKAGE) and
                path.name == "TEST-" + suite + ".xml", "AUTHORITY_SUITE_IDENTITY_MISMATCH")
        expected = REQUIRED[suite.removeprefix(PACKAGE)]
        cases = root.findall("testcase")
        require(root.get("tests") == str(len(expected)) and len(cases) == len(expected)
                and {x.get("name") for x in cases} == expected, "AUTHORITY_METHOD_SET_MISMATCH " + suite)
        require(all(root.get(key) == "0" for key in ("errors", "failures", "skipped")),
                "AUTHORITY_SUITE_NOT_GREEN " + suite)
        for case in cases:
            require(case.get("classname") == suite, "AUTHORITY_CASE_CLASS_MISMATCH")
            require(not any(child.tag in BAD for child in case), "AUTHORITY_CASE_NOT_CLEAN " + case.get("name", ""))
        result.append({"suite": suite, "methods": sorted(expected),
                       "surefireFile": path.name, "sha256": hashlib.sha256(data).hexdigest()})
    return {"schema": "RM-SIGNED-JWT-AUTHORITY-E1A/v1", "sha": sha, "status": "PASS",
            "total": 12, "authorityCases": 4, "iamSellerPostgresqlCases": 6,
            "scope": "TEST_AUTHORITY_AND_SERVICE; NOT LIVE_IDP_OR_HTTP_ADMISSION",
            "productionAdmission": "HOLD", "suites": result}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sha = os.environ.get("GITHUB_SHA", "")
    checkout = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    report = verify(args.reports, sha, checkout)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "exact-sha.txt").write_text(sha + "\n", encoding="utf-8")
    (args.output / "authority-manifest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("AUTHORITY_E1A_4_OF_4_PASS; IAM_SELLER_R0_6_OF_6_SIGNED_JWT_PG_EXECUTED; TOTAL_12_OF_12_PASS")


if __name__ == "__main__":
    main()
