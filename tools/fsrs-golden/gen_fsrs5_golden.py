"""Generate FSRS-5 golden vectors from the official py-fsrs implementation.

The Kotlin kernel in shared/domain is only "FSRS-5" if its numbers agree with the
reference implementation on the same review records. This script produces that
reference record; `Fsrs5ReferenceParityTest` replays it in Kotlin and fails on any
interval or stability/difficulty difference.

Pinned reference: fsrs==5.1.3 (the last py-fsrs line that implements FSRS-5 with the
19 default weights). FSRS-6 changed the weight count and the formulas, so the version
pin is part of the contract.

Usage (from the repository root):

    python -m venv .venv-fsrs
    .venv-fsrs/Scripts/python -m pip install fsrs==5.1.3      # Windows
    .venv-fsrs/bin/python -m pip install fsrs==5.1.3          # macOS/Linux
    .venv-fsrs/Scripts/python tools/fsrs-golden/gen_fsrs5_golden.py \
        --out shared/domain/src/jvmTest/resources/fsrs5-golden.json   # Windows
    .venv-fsrs/bin/python tools/fsrs-golden/gen_fsrs5_golden.py \
        --out shared/domain/src/jvmTest/resources/fsrs5-golden.json   # macOS/Linux

Then:

    ./gradlew :shared:domain:jvmTest --tests '*Fsrs5*'
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta, timezone

import fsrs
from fsrs import Card, Rating, Scheduler

# Day offsets are explicit, not "whenever the scheduler said": the Kotlin replay must be
# handed the same review records, including overdue and same-day reviews.
SEQUENCES = [
    {
        "name": "fresh-good-on-schedule",
        "steps": [(0, Rating.Good), (3, Rating.Good), (14, Rating.Good),
                  (45, Rating.Good), (120, Rating.Good), (300, Rating.Good)],
    },
    {
        "name": "fresh-again-recovers",
        "steps": [(0, Rating.Again), (1, Rating.Good), (3, Rating.Hard),
                  (8, Rating.Good), (20, Rating.Easy), (90, Rating.Good)],
    },
    {
        "name": "same-day-repeats",
        "steps": [(0, Rating.Good), (0, Rating.Good), (0, Rating.Again),
                  (1, Rating.Hard), (1, Rating.Good), (7, Rating.Easy)],
    },
    {
        "name": "overdue-and-lapses",
        "steps": [(0, Rating.Good), (10, Rating.Good), (25, Rating.Again),
                  (26, Rating.Hard), (40, Rating.Again), (41, Rating.Good),
                  (200, Rating.Easy)],
    },
    {
        "name": "easy-chain-hits-max-interval",
        "steps": [(0, Rating.Easy), (16, Rating.Easy), (60, Rating.Easy),
                  (200, Rating.Easy), (900, Rating.Easy), (5_000, Rating.Easy),
                  (12_000, Rating.Easy), (30_000, Rating.Easy)],
    },
    {
        "name": "all-again-difficulty-ceiling",
        "steps": [(0, Rating.Again), (1, Rating.Again), (2, Rating.Again),
                  (3, Rating.Again), (4, Rating.Again), (5, Rating.Again),
                  (6, Rating.Again), (7, Rating.Again)],
    },
    {
        "name": "hard-only",
        "steps": [(0, Rating.Hard), (2, Rating.Hard), (6, Rating.Hard),
                  (16, Rating.Hard), (40, Rating.Hard), (100, Rating.Hard)],
    },
]

BASE = datetime(2026, 1, 1, tzinfo=timezone.utc)


def build_scheduler() -> Scheduler:
    # Empty learning/relearning steps: this product schedules in whole days and has no
    # intra-day relearning ladder, so the kernel compares against pure FSRS.
    return Scheduler(
        parameters=fsrs.fsrs.DEFAULT_PARAMETERS,
        desired_retention=0.9,
        learning_steps=(),
        relearning_steps=(),
        maximum_interval=36_500,
        enable_fuzzing=False,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    scheduler = build_scheduler()
    sequences = []
    for spec in SEQUENCES:
        card = Card(card_id=1)
        steps = []
        for day, rating in spec["steps"]:
            card, _ = scheduler.review_card(
                card, rating, review_datetime=BASE + timedelta(days=day)
            )
            interval = (card.due - (BASE + timedelta(days=day))).days
            steps.append({
                "day": day,
                "rating": int(rating),
                "state": int(card.state),
                "stability": card.stability,
                "difficulty": card.difficulty,
                "intervalDays": interval,
            })
        sequences.append({"name": spec["name"], "steps": steps})

    payload = {
        "reference": f"py-fsrs {fsrs.__dict__.get('__version__', '5.1.3')}",
        "scheduler": {
            "parameters": list(fsrs.fsrs.DEFAULT_PARAMETERS),
            "desiredRetention": 0.9,
            "maximumIntervalDays": 36_500,
            "fuzzing": False,
            "learningSteps": [],
            "relearningSteps": [],
        },
        "note": "state 1=Learning 2=Review 3=Relearning; ratings 1=Again 2=Hard 3=Good 4=Easy",
        "sequences": sequences,
    }
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()
