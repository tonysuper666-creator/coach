import unittest
from summarize import summarize


def event(kind, ns, action=1, page=2, image=3):
    return dict(kind=kind, ns=ns, action=action, page=page, image=image)


class MeasurementEvidenceTest(unittest.TestCase):
    def report(self, *events):
        return summarize(dict(complete=True, events=list(events)))

    def test_stale_page_with_a_new_action_does_not_establish_target_display(self):
        report = self.report(event("input_next", 100), event("target_draw_commands", 110, page=1),
                             event("pixels_observed", 120, page=1))
        self.assertFalse(report["samples"][0]["complete"])

    def test_handler_draw_and_pixels_are_different_boundaries(self):
        report = self.report(event("input_next", 100), event("lookup_miss", 105),
                             event("target_draw_commands", 110), event("frame_submitted", 115),
                             event("pixels_observed", 120))
        row = report["samples"][0]
        self.assertEqual((row["handlerToDrawCommandsMs"], row["handlerToSubmissionMs"],
                          row["handlerToPixelsUpperBoundMs"]), (10 / 1e6, 15 / 1e6, 20 / 1e6))
        self.assertFalse(row["decodedCacheHit"])
        self.assertFalse(report["firstPresentLatencyVerified"])
        self.assertFalse(report["memoryBudgetVerified"])

    def test_decode_complete_without_pixels_is_not_display(self):
        report = self.report(event("input_next", 100), event("decode_ready", 110),
                             event("target_draw_commands", 120))
        self.assertFalse(report["samples"][0]["complete"])

    def test_previous_capture_and_next_input_cannot_complete_an_earlier_action(self):
        report = self.report(event("pixels_observed", 90), event("input_next", 100),
                             event("target_draw_commands", 110), event("input_next", 120, action=2, page=3),
                             event("pixels_observed", 130))
        self.assertFalse(report["samples"][0]["complete"])

    def test_incomplete_trace_is_rejected_and_absent_lookup_is_unknown(self):
        with self.assertRaises(ValueError):
            summarize(dict(complete=False, events=[]))
        report = self.report(event("input_next", 100), event("target_draw_commands", 110), event("pixels_observed", 120))
        self.assertIsNone(report["samples"][0]["decodedCacheHit"])


if __name__ == "__main__":
    unittest.main()
