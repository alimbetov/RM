import unittest
import uuid
from copy import deepcopy
from decimal import Decimal
from verify_ret04_checkout_e6c import check_rows

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
if __name__=="__main__":
    unittest.main()
