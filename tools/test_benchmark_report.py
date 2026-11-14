import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("benchmark_report", Path(__file__).with_name("benchmark_report.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ReportTests(unittest.TestCase):
    def row(self):
        return {"benchmark": "dev.dialogmenu.Example", "mode": "sample", "params": {"rows": "12"},
                "jdkVersion": "25", "threads": 1, "forks": 2,
                "primaryMetric": {"score": 10, "scoreUnit": "us/op", "scorePercentiles": {"95.0": 20}}}

    def test_comparison_direction(self):
        old = self.row(); new = copy.deepcopy(old); new["primaryMetric"]["score"] = 12
        self.assertIn("+20.0%", report.report({report.key(new): new}, {report.key(old): old}))

    def test_refuse_different_runtime(self):
        old = self.row(); new = copy.deepcopy(old); new["jdkVersion"] = "21"
        with self.assertRaises(ValueError): report.compatible(old, new)

    def test_refuse_different_units(self):
        old = self.row(); new = copy.deepcopy(old); new["primaryMetric"]["scoreUnit"] = "ms/op"
        with self.assertRaises(ValueError): report.compatible(old, new)

    def test_duplicate_and_empty_results(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "data.json"
            for rows in ([], [self.row(), self.row()]):
                file.write_text(json.dumps(rows))
                with self.assertRaises(ValueError): report.load(file)

    def test_missing_scenario_is_visible(self):
        row = self.row()
        self.assertIn("Candidate only", report.report({report.key(row): row}, {}))


if __name__ == "__main__": unittest.main()
