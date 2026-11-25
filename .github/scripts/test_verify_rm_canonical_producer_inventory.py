import unittest
from verify_rm_canonical_producer_inventory import alternate_producer


class CanonicalProducerInventoryScannerTest(unittest.TestCase):
    def test_quoted_insert_identifiers_require_review(self):
        for source in (
            'INSERT INTO "crm".sales_order_status_event VALUES (...)',
            'INSERT INTO crm."inv_stock_movement" VALUES (...)',
            r'jdbc.update("INSERT INTO \"crm\".\"inv_stock_movement\" VALUES (?)", value);',
        ):
            with self.subTest(source=source):
                self.assertTrue(alternate_producer(source, ".java"))

    def test_adjacent_java_literals_require_review(self):
        self.assertTrue(alternate_producer(
            'jdbc.update("INSERT INTO crm." + "inv_stock_movement VALUES (?)", value);', ".java"))
        self.assertTrue(alternate_producer(
            'jdbc.update("INS" + "ERT INTO crm.sales_order_status_event VALUES (?)", value);', ".java"))

    def test_other_write_paths_require_review(self):
        for source, suffix in (
            ('MERGE INTO "crm".sales_order_status_event USING x ON ...', ".sql"),
            ('COPY crm."inv_stock_movement" FROM STDIN', ".sql"),
            ('@Table(name="inv_stock_movement", schema="crm")', ".java"),
            ('INSERT INTO crm.sales_order_status_event VALUES (...)', ".xml"),
        ):
            with self.subTest(source=source):
                self.assertTrue(alternate_producer(source, suffix))

    def test_normal_literal_and_read_are_not_alternates(self):
        self.assertFalse(alternate_producer(
            'jdbc.update("INSERT INTO crm.sales_order_status_event VALUES (?)", value);', ".java"))
        self.assertFalse(alternate_producer(
            'SELECT * FROM crm.inv_stock_movement WHERE tenant_id = ?', ".java"))


if __name__ == "__main__":
    unittest.main()
