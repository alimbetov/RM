import unittest
import uuid
from copy import deepcopy
from decimal import Decimal
from verify_ret04_checkout_e6c import check_rows,check_registration,FIELDS,PHASES

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
if __name__=="__main__":
    unittest.main()
