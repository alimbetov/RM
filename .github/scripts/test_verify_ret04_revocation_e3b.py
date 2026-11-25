"""Fail-closed E.3B verifier unit checks."""
import copy
import unittest
from verify_ret04_revocation_e3b import check_order, check_iam, check_business, COUNTS
from verify_ret04_evidence import TABLES


class RevocationEvidenceTest(unittest.TestCase):
    def history(self, warehouse=True):
        meta={"tenant":"t","owner":"o","staff":"s"}
        fixed=dict(meta,warehouse="w",role="r",permission="INVENTORY_RECEIVE",sha="sha",method="test")
        def state(scope,version,grant,we,pe):
            return dict(fixed,scope=scope,**dict(zip(COUNTS,(version,grant,1,1,we,we,pe,pe))))
        first=state("ACTIVE",0,1,0,0)
        after=state("REVOKED",1,1,1,0) if warehouse else state("ACTIVE",0,0,0,1)
        return [first,dict(first),after,dict(after)],meta
    def verify_iam(self,history,meta,warehouse=True):
        check_iam(history,meta,"w","INVENTORY_RECEIVE",warehouse)
    def test_warehouse_and_permission(self):
        for warehouse in (True,False):
            h,m=self.history(warehouse)
            self.verify_iam(h,m,warehouse)
    def test_wrong_actor(self):
        h,m=self.history()
        h[2]["owner"]="foreign"
        with self.assertRaisesRegex(ValueError,"identity changed"):
            self.verify_iam(h,m)
    def test_missing_audit(self):
        h,m=self.history()
        h[2]["warehouseOwnerEvents"]=0
        with self.assertRaisesRegex(ValueError,"exactly once"):
            self.verify_iam(h,m)
    def test_dirty_iam_read(self):
        h,m=self.history()
        h[1]["scope"]="REVOKED"
        with self.assertRaisesRegex(ValueError,"uncommitted"):
            self.verify_iam(h,m)
    def test_replay_changes_iam(self):
        h,m=self.history(False)
        h[3]["permissionEvents"]=2
        with self.assertRaisesRegex(ValueError,"replay changed"):
            self.verify_iam(h,m,False)
    def log(self,outcome="DENIED"):
        reason="WAREHOUSE_SCOPE_REQUIRED"
        return "\n".join(["BSR PG schedule blocker=1 waiter=2 observed=true first=COMMITTED second="+outcome]+
                ["RET04E3B replay="+v+" denied reason="+reason for v in ("existing-principal","original-bearer","fresh-bearer")])
    def test_both_commit_orders(self):
        check_order(self.log(),False,"WAREHOUSE_SCOPE_REQUIRED")
        check_order(self.log("COMMITTED"),True,"WAREHOUSE_SCOPE_REQUIRED")
    def test_wrong_lock_order(self):
        with self.assertRaisesRegex(ValueError,"locking edge"):
            check_order(self.log("COMMITTED"),False,"WAREHOUSE_SCOPE_REQUIRED")
    def test_missing_replay(self):
        with self.assertRaisesRegex(ValueError,"replay denial"):
            check_order(self.log().replace("fresh-bearer","other"),False,"WAREHOUSE_SCOPE_REQUIRED")
    def test_denied_business_mutation(self):
        row=({"tenant":"t"},{t:(0,"digest") for t in TABLES},
             {"sales_return_request":[{"state":"QUARANTINED","row_version":6}]})
        h=[copy.deepcopy(row) for _ in range(7)]
        check_business(h,None)
        h[-1][1]["inv_stock_movement"]=(1,"changed")
        with self.assertRaisesRegex(ValueError,"denied business mutation"):
            check_business(h,None)

if __name__=="__main__":
    unittest.main()
