import csv
import tempfile
import unittest
from pathlib import Path
from radar_competition_profile import percentile,plan_summary,competition,read_samples,analyze


class ProfileTest(unittest.TestCase):
    def test_nearest_rank(self):
        self.assertEqual(percentile([i*1000000 for i in range(1,101)]),95)
        with self.assertRaises(ValueError):
            percentile([])
    def test_plan_loop_metrics_without_summing(self):
        child={"Node Type":"Index Scan","Actual Rows":3,"Plan Rows":2,
               "Actual Loops":10,"Actual Total Time":.4,"Shared Hit Blocks":17}
        plan={"Node Type":"Nested Loop","Actual Rows":30,"Plan Rows":20,
              "Actual Loops":1,"Actual Total Time":8,"Shared Hit Blocks":21,"Plans":[child]}
        result=plan_summary([{"Plan":plan,"Execution Time":9}])
        self.assertEqual(result["executionMs"],9)
        self.assertEqual(result["root"]["sharedHitBlocks"],21)
        self.assertEqual(result["nodes"][1]["rowsAcrossLoops"],30)
        self.assertEqual(result["nodes"][1]["inclusiveMsAcrossLoops"],4)
    def test_missing_analyze_rejected(self):
        with self.assertRaises(ValueError):
            plan_summary([{"Plan":{"Node Type":"Seq Scan"}}])
    def test_competition_recomputes_gate_and_checks_raw_arms(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            samples={}
            base=[];mixed=[]
            (root/"competition-contract.txt").write_text("commandsPerBlock=1000\nminOverlappingReads=100\n")
            with (root/"100000-lock-sampling.csv").open("w",newline="") as f:
                writer=csv.writer(f)
                writer.writerow(["block","mixed","samples","positive","reads"])
                for i,m in enumerate([False,True,True,False,False,True],1):
                    values=[1200000 if m else 1000000]*1000
                    samples[f"100000-command-block{i}"]=values
                    (mixed if m else base).extend(values)
                    writer.writerow([i,str(m).lower(),1,0,2 if m else 0])
                    with (root/f"100000-block{i}-intervals.csv").open("w",newline="") as intervals:
                        w=csv.writer(intervals);w.writerow(["kind","sample","startNanos","endNanos"])
                        end=0
                        for j,value in enumerate(values,1):
                            w.writerow(["command",j,end,end+value]);end+=value
                        if m:
                            w.writerow(["read",1,0,10000000]);w.writerow(["read",2,10000000,20000000])
            samples.update({"100000-command-baseline":base,"100000-command-mixed":mixed,
                            "100000-mixed-reader":[10000000]*6})
            result=competition(root,samples)
            self.assertAlmostEqual(result["commandDegradationPercent"],20)
            self.assertFalse(result["commandWithinBudget"])
            self.assertTrue(result["readerTailSampleWarning"])
            self.assertEqual(result["overlappingReads"],6)
            self.assertFalse(result["overlapQualified"])
            samples["100000-command-mixed"]=[1]*3000
            with self.assertRaisesRegex(ValueError,"raw blocks"):
                competition(root,samples)
    def test_nonsequential_sample_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"sample.csv"
            p.write_text("sample,nanos\n2,5000\n")
            with self.assertRaises(ValueError):
                read_samples(p)
    def test_incomplete_run_never_becomes_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/"runner.txt").write_text("sha="+"a"*40+"\n")
            result=analyze(root,"a"*40)
            self.assertEqual(result["diagnosticStatus"],"INCOMPLETE")
            self.assertIsNone(result["competition"])
            with self.assertRaisesRegex(ValueError,"SHA mismatch"):
                analyze(root,"b"*40)
if __name__=="__main__":
    unittest.main()
