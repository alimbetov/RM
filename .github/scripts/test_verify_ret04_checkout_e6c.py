import unittest
import uuid
from copy import deepcopy
from decimal import Decimal
from verify_ret04_checkout_e6c import check_rows,check_registration,check_physical,FIELDS,PHASES

class CheckoutEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.meta={k:str(uuid.uuid4()) for k in ("tenant","staff","buyer")}
        self.row={k:str(uuid.uuid4()) for k in ("return_id","order_id","identity_id","challenge_id",
          "registration_event","line_id","offer_id","sku","storefront_id","qr_id","handover_id","warehouse_id")}
        self.row.update({k:self.meta["buyer"] for k in ("return_buyer","buyer","identity_buyer","identity_owner","registration_buyer")})
        self.row.update(qr_issuer=self.meta["staff"],handover_staff=self.meta["staff"],identity_state="CURRENT",
          verified=True,challenge_purpose="REGISTRATION",registration_purpose="REGISTRATION",consumed=True,
          registered_before_order=True,delivery_rows=1,checkout_challenges=0,
          storefront_state="PUBLISHED",offer_state="PUBLISHED",item_state="PUBLISHED",item_kind="PRODUCT",
          qr_state="ACTIVE",price_rows=1,quantity=5,unit_price=Decimal("100"),offer_price=Decimal("100"),
          total_amount=Decimal("500"),currency="KZT",order_currency="KZT",offer_currency="KZT",
          fulfillment_mode="PICKUP_PROOF_REQUIRED",receipt_sha="a"*64,receipt_key_sha="b"*64)
        x=self.row;m=self.meta
        self.log=(f"RET04E6A registration proof actor={m['buyer']} identity={x['identity_id']} challenge={x['challenge_id']}\n"
                  f"RET04E6B verified checkout tenant={m['tenant']} buyer={m['buyer']} order={x['order_id']} offer={x['offer_id']} sku={x['sku']}")
        self.returns=[{"id":x["return_id"]}]
    def check(self,**changes):
        x=dict(self.row,**changes)
        check_rows([x],self.meta,self.returns,self.log)
    def test_valid(self):
        self.check()
    def test_wrong_buyer(self):
        with self.assertRaisesRegex(ValueError,"buyer mismatch"):
            self.check(identity_owner=str(uuid.uuid4()))
    def test_wrong_registration_purpose_or_unconsumed(self):
        for changes in ({"challenge_purpose":"CHECKOUT"},{"consumed":False},{"checkout_challenges":1}):
            with self.assertRaisesRegex(ValueError,"registration proof"):
                self.check(**changes)
    def test_wrong_publication(self):
        with self.assertRaisesRegex(ValueError,"publication proof"):
            self.check(qr_state="REVOKED")
    def test_wrong_economics(self):
        with self.assertRaisesRegex(ValueError,"economics"):
            self.check(total_amount=Decimal("501"))
    def test_missing_marker(self):
        with self.assertRaisesRegex(ValueError,"marker"):
            check_rows([self.row],self.meta,self.returns,"")
    def test_wrong_return(self):
        with self.assertRaisesRegex(ValueError,"return binding"):
            self.check(return_id=str(uuid.uuid4()))
    def two_orders(self):
        # Same buyer, registration, SKU, offer, QR, quantity, price, fingerprint.
        # Different orders and receipts, just like RET04 shared SKU scenarios.
        other=dict(self.row,**{k:str(uuid.uuid4()) for k in
                  ("return_id","order_id","line_id","handover_id")},receipt_key_sha="c"*64)
        returns=self.returns+[{"id":other["return_id"]}]
        m=self.meta
        log=self.log+f"\nRET04E6B verified checkout tenant={m['tenant']} buyer={m['buyer']} order={other['order_id']} offer={other['offer_id']} sku={other['sku']}"
        return other,returns,log
    def test_same_fingerprint_distinct_receipts_valid(self):
        other,returns,log=self.two_orders()
        self.assertEqual(other["receipt_sha"],self.row["receipt_sha"])
        check_rows([self.row,other],self.meta,returns,log)
    def test_duplicate_receipt(self):
        other,returns,log=self.two_orders()
        other.update(receipt_key_sha=self.row["receipt_key_sha"],receipt_sha="d"*64)
        with self.assertRaisesRegex(ValueError,"duplicate checkout receipt key"):
            check_rows([self.row,other],self.meta,returns,log)
    def test_duplicate_order(self):
        other,returns,log=self.two_orders()
        other["order_id"]=self.row["order_id"]
        with self.assertRaisesRegex(ValueError,"duplicate checkout order"):
            check_rows([self.row,other],self.meta,returns,log)
    def test_duplicate_handover(self):
        other,returns,log=self.two_orders()
        other["handover_id"]=self.row["handover_id"]
        with self.assertRaisesRegex(ValueError,"duplicate checkout line/handover"):
            check_rows([self.row,other],self.meta,returns,log)
    def test_invalid_key_digest(self):
        with self.assertRaisesRegex(ValueError,"invalid receipt key digest"):
            self.check(receipt_key_sha="invalid")
class RegistrationTransitionTest(unittest.TestCase):
    def setUp(self):
        buyer,identity,challenge,event,delivery=[str(uuid.uuid4()) for _ in range(5)]
        self.meta={"buyer":buyer,"tenant":str(uuid.uuid4())}
        before=dict.fromkeys(FIELDS)
        before.update(buyer=buyer,account_status="ACTIVE",identities=0,challenges=0,events=0,deliveries=0)
        pending=dict(before,identities=1,challenges=1,deliveries=1,
                     identity_id=identity,identity_buyer=buyer,identity_state="PENDING",
                     pending_reason="REGISTRATION",pending_owner=buyer,
                     identity_created_at="2026-01-01T00:00:00+00:00",
                     challenge_id=challenge,challenge_identity=identity,challenge_purpose="REGISTRATION",
                     challenge_created_at="2026-01-01T00:00:01+00:00",expires_at="2026-01-01T00:15:01+00:00",
                     delivery_id=delivery,delivery_identity=identity,delivery_challenge=challenge)
        when="2026-01-01T00:00:02+00:00"
        current=dict(pending,events=1,identity_state="CURRENT",pending_reason=None,pending_owner=None,
                     current_owner=buyer,verified_at=when,consumed_at=when,event_at=when,
                     registration_event=event,registration_buyer=buyer,event_identity=identity,
                     event_challenge=challenge,registration_purpose="REGISTRATION")
        self.states=dict(zip(PHASES,(before,pending,deepcopy(pending),current,deepcopy(current))))
        self.lineage=[dict(buyer=buyer,identity_id=identity,challenge_id=challenge,registration_event=event)]
    def check(self):
        check_registration(self.states,self.meta,self.lineage)
    def test_registration_valid(self):
        self.check()
    def test_preexisting(self):
        self.states["BEFORE"]["identities"]=1
        with self.assertRaisesRegex(ValueError,"preexisting"):
            self.check()
    def test_wrong_purpose_mutation(self):
        self.states["WRONG_PURPOSE_DENIED"]["consumed_at"]="2026-01-01T00:00:02+00:00"
        with self.assertRaisesRegex(ValueError,"wrong purpose"):
            self.check()
    def test_replay_extra_event(self):
        self.states["REPLAY_DENIED"]["events"]=2
        with self.assertRaisesRegex(ValueError,"replay changed"):
            self.check()
    def test_current_wrong_owner(self):
        self.states["CURRENT"]["current_owner"]=str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError,"current invariant"):
            self.check()
    def test_expired_proof(self):
        for k in ("verified_at","consumed_at","event_at"):
            self.states["CURRENT"][k]="2026-01-01T00:16:01+00:00"
        with self.assertRaisesRegex(ValueError,"commit chronology"):
            self.check()
    def test_wrong_event(self):
        self.states["CURRENT"]["event_challenge"]=str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError,"event binding"):
            self.check()
    def test_foreign_checkout(self):
        self.lineage[0]["identity_id"]=str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError,"checkout binding"):
            self.check()
    def test_missing_phase(self):
        del self.states["WRONG_PURPOSE_DENIED"]
        with self.assertRaisesRegex(ValueError,"phase order"):
            self.check()
class PhysicalEvidenceTest(unittest.TestCase):
    def setUp(self):
        ids={k:str(uuid.uuid4()) for k in ("return_id","order_id","handover_id","sku","warehouse_id",
              "buyer","posting_id","intake_id","movement_id","staff")}
        self.meta={"staff":ids["staff"]}
        self.row=dict(ids,order_status="COMPLETED",reservation_state="FULFILLED",quantity=5,
            fulfilled_quantity=5,reservation_quantity=5,reservation_warehouse=ids["warehouse_id"],
            reservation_item=ids["sku"],return_quantity=2,posted_quantity=2,intake_quantity=2,
            allocated_quantity=2,disposition="QUARANTINE",posting_sellable_movement=None,
            posting_quarantine_movement=ids["intake_id"],intake_return=ids["return_id"],
            posting_actor=ids["staff"],intake_actor=ids["staff"],posting_warehouse=ids["warehouse_id"],
            intake_warehouse=ids["warehouse_id"],posting_item=ids["sku"],intake_item=ids["sku"],
            intake_before=0,intake_after=2,invoice_rows=0)
        self.tables={k:(1,"unused") for k in ("sales_return_posting","inv_return_quarantine_movement",
            "sales_pickup_handover","sales_pickup_handover_line","sales_order","sales_order_line")}
        self.tables["fin_invoice"]=(0,"unused")
        self.req=dict(id=ids["return_id"],order_id=ids["order_id"],source_handover_id=ids["handover_id"],
                      buyer_user_id=ids["buyer"],state="QUARANTINED",row_version=6)
        self.proj=dict(sales_return_request=[self.req],sales_return_quarantine_resolution=[],
                       inv_stock_movement=[],sales_return_event=[])
    def check(self):
        check_physical([self.row],self.meta,self.tables,self.proj)
    def settle(self,action):
        x=self.row
        self.req.update(state="RESTOCKED" if action=="RESTOCK" else "DISPOSED",row_version=7)
        self.res=dict(return_id=x["return_id"],action_code=action,warehouse_id=x["warehouse_id"],
            item_id=x["sku"],actor_user_id=x["staff"],quantity=2,before_quantity=2,after_quantity=0,
            stock_movement_id=x["movement_id"] if action=="RESTOCK" else None)
        self.proj["sales_return_quarantine_resolution"]=[self.res]
        self.proj["sales_return_event"]=[dict(return_id=x["return_id"],from_state="QUARANTINED",
            to_state=self.req["state"],expected_row_version=6,actor_user_id=x["staff"])]
        if action=="RESTOCK":
            self.proj["inv_stock_movement"]=[dict(id=x["movement_id"],location_id=x["warehouse_id"],
                item_id=x["sku"],delta_quantity=2,balance_before=15,balance_after=17)]
    def test_quarantined(self):
        self.check()
    def test_restock(self):
        self.settle("RESTOCK");self.check()
    def test_dispose(self):
        self.settle("DISPOSE");self.check()
    def test_wrong_source(self):
        self.req["source_handover_id"]=str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError,"source binding"):
            self.check()
    def test_wrong_capacity(self):
        self.row["allocated_quantity"]=6
        with self.assertRaisesRegex(ValueError,"capacity"):
            self.check()
    def test_original_posting_sellable(self):
        self.row["posting_sellable_movement"]=str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError,"posting invariant"):
            self.check()
    def test_wrong_restock_movement(self):
        self.settle("RESTOCK")
        self.proj["inv_stock_movement"][0]["item_id"]=str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError,"restock movement"):
            self.check()
    def test_dispose_stock_pointer(self):
        self.settle("DISPOSE");self.res["stock_movement_id"]=self.row["movement_id"]
        with self.assertRaisesRegex(ValueError,"dispose linked"):
            self.check()
    def test_wrong_terminal_event(self):
        self.settle("RESTOCK");self.proj["sales_return_event"][0]["expected_row_version"]=5
        with self.assertRaisesRegex(ValueError,"terminal event invariant"):
            self.check()
    def test_invoice_side_effect(self):
        self.tables["fin_invoice"]=(1,"unused")
        with self.assertRaisesRegex(ValueError,"invoice side effect"):
            self.check()
if __name__=="__main__":
    unittest.main()
