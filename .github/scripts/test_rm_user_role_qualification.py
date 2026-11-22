"""Synthetic fail-closed tests for UR-R3.1 evidence reporting (stdlib only)."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rm_user_role_qualification import assess_role_cases

ROOT=Path(__file__).resolve().parents[2]


class RoleEvidenceContractTest(unittest.TestCase):
    def test_passing_exact_postgres_cases_qualify_only_after_qa_admission(self):
        methods = [
            "urR31AllFiveMembershipRolesHaveFallbackReadButNoImplicitWrite",
            "urR31ExplicitGrantsAuthorizeEachRoleAndRevocationFencesNextCommand",
            "urR31TenantAAndBEnforceIndependentGrantsAndNoForeignWrites",
            "urR31DualTenantMemberHasDifferentPermissionsAndRevocationIsLocal",
            "urR31SuspendedActorAndMembershipCannotWriteDespiteExistingGrant",
            "urR31UnassignedStaffAndMissingActorAreDeniedWithoutSideEffects",
        ]
        cases = {"kms-postgres-surefire": {
            ("UserRoleMatrixR31QualificationTest", x) for x in methods
        }}
        cases["kms-postgres-surefire"].update({
            ("SalesInventoryRbacQualificationTest", "urR32SalesConfirmDenyPreservesOrderReservationAndStatusHistory"),
            ("SalesInventoryRbacQualificationTest", "urR32InventoryGrantRevokeAndForeignActorPreserveLedger"),
            ("CrmR1aQualificationTest", "urR32CrmWriterRevocationAndForeignTenantLeaveCustomerLedgerUnchanged"),
            ("FilesR10bQualificationTest", "urR32FilesTenantAndRevocationNeverPromoteUnapprovedBytes"),
            ("OrganizationLocationLifecycleQualificationTest", "urR32OrganizationLocationGrantRevocationAndForeignTenantHaveZeroEffects"),
        })
        cases["kms-postgres-surefire"].update({
            ("TenantRbacRevocationQualificationTest", "activeCommandCompletesBeforeConcurrentRevocationCommits"),
            ("TenantRbacRevocationQualificationTest", "commandCannotProceedUntilEarlierRevocationCommits"),
            ("CommercialCoreE2EQualificationTest", "postgresSec09SuspendCommitsBeforeCheckoutMustFailWithoutOrderEffects"),
            ("CommercialCoreE2EQualificationTest", "postgresSec09CheckoutCommitsBeforeSuspendKeepsOneAcceptedSnapshot"),
            ("InventoryTransferStocktakeQualificationTest", "urR33PostgresTransferCommitsBeforeReconciliationSnapshot"),
            ("InventoryTransferStocktakeQualificationTest", "urR33PostgresReconciliationSnapshotCommitsBeforeTransferVisibility"),
            ("InventoryTransferStocktakeQualificationTest", "durableReconciliationRollbackDoesNotAdvanceCursorOrPersistFindings"),
            ("InventoryTransferStocktakeQualificationTest", "transferWritesExactlyTwoLegsAndReplaysWithoutDuplicateBalances"),
        })
        result=assess_role_cases(ROOT,cases,"a"*40,True,write=False)
        self.assertEqual(result["mandatory"],19)
        self.assertEqual(result["verifiedInternal"],19)
        self.assertEqual(result["gate"],"USER_ROLE_SERVICE_QUALIFIED")
        held=assess_role_cases(ROOT,cases,"a"*40,False,write=False)
        self.assertEqual(held["executedPostgresql"],19)
        self.assertEqual(held["verifiedInternal"],0)
        self.assertEqual(held["gate"],"HOLD")

    def test_missing_or_h2_only_execution_cannot_qualify(self):
        absent=assess_role_cases(ROOT,{},"b"*40,True,write=False)
        self.assertEqual(absent["gate"],"HOLD")
        self.assertTrue(all(r["status"]=="NOT_RUN" for r in absent["cases"]))
        h2=assess_role_cases(ROOT,{
            "kms-h2-surefire": {
                ("UserRoleMatrixR31QualificationTest",
                 "urR31AllFiveMembershipRolesHaveFallbackReadButNoImplicitWrite")
            }
        },"b"*40,True,write=False)
        self.assertEqual(h2["verifiedInternal"],0)
        self.assertEqual(h2["gate"],"HOLD")

    def test_partial_completion_keeps_case_pending(self):
        partial=assess_role_cases(ROOT,{
            "kms-postgres-surefire": {
                ("UserRoleMatrixR31QualificationTest",
                 "urR31AllFiveMembershipRolesHaveFallbackReadButNoImplicitWrite")
            }
        },"c"*40,True,write=False)
        self.assertEqual(partial["executedPostgresql"],1)
        self.assertEqual(partial["verifiedInternal"],1)
        self.assertEqual(partial["gate"],"HOLD")


if __name__=="__main__":
    unittest.main()
