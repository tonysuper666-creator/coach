"""Check four published OpenStax answers with the repository's local essay rules."""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

import pymupdf


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
SERVER_SRC = REPO / "server/app/src"
PACKAGE = Path("kotlin/com/coach/server")

# Chapter, self-check question, answer-key PDF page, topic, teaching PDF pages.
CASES = (
    (1, 1, 921, "Scarcity", (26,)),
    (1, 5, 921, "Microeconomics and Macroeconomics", (31, 41)),
    (2, 1, 921, "Budget constraint", (45, 47, 49)),
    (2, 4, 922, "Production possibilities frontier and budget constraint", (49, 51, 52)),
)


def read_cases(pdf):
    cases = []
    with pymupdf.open(pdf) as book:
        if len(book) != 995 or "ANSWER KEY" not in book[920].get_text():
            raise ValueError("Expected the 995-page Principles of Economics 3e PDF")
        for chapter, question, key_page, topic, page_numbers in CASES:
            key = book[key_page - 1].get_text()
            sections = re.split(r"(?m)^Chapter\s+(\d+)[ \t]*\r?$", key)
            # The top of page 922 continues chapter 2 without a new heading.
            by_chapter = dict(zip(sections[1::2], sections[2::2]))
            section = by_chapter.get(str(chapter), sections[0])
            match = re.search(
                rf"(?ms)^{question}\s*\.\s*(.+?)(?=^\d+\s*\. |^[ \t]+\d+[ \t]*\r?$|\Z)", section
            )
            if not match:
                raise ValueError(f"Missing self-check answer for chapter {chapter}, question {question}")
            cases.append({
                "case": f"ch{chapter}-q{question}",
                "topic": topic,
                "answer": " ".join(match[1].split()),
                "pages": {str(n): book[n - 1].get_text() for n in page_numbers},
            })
    return cases


def run(pdf, gradle_home):
    cases = read_cases(pdf)
    cache = gradle_home / "caches/modules-2/files-2.1"
    versions = tomllib.loads((REPO / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
    kotlin = versions["kotlin"]

    def jar(group, artifact, version):
        directory = cache / group / artifact / version
        path = next(directory.glob(f"*/{artifact}-{version}.jar"), None)
        if path is None:
            raise FileNotFoundError(f"Missing {group}:{artifact}:{version} in {gradle_home}")
        return str(path)

    compiler = [
        jar("org.jetbrains.kotlin", artifact, kotlin) for artifact in
        ("kotlin-compiler-embeddable", "kotlin-script-runtime", "kotlin-daemon-embeddable")
    ]
    compiler.append(jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"))
    libraries = [
        jar("org.jetbrains.kotlin", "kotlin-stdlib", kotlin),
        jar("org.jetbrains.kotlin", "kotlin-test", kotlin),
        jar("org.jetbrains.kotlin", "kotlin-test-junit", kotlin),
        jar("org.jetbrains", "annotations", "13.0"),
        jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", versions["coroutines"]),
        jar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", versions["serialization"]),
        jar("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", versions["serialization"]),
        jar("junit", "junit", "4.13.2"),
        jar("org.hamcrest", "hamcrest-core", "1.3"),
    ]
    java = Path(os.environ["JAVA_HOME"]) / "bin/java" if "JAVA_HOME" in os.environ else "java"
    with tempfile.TemporaryDirectory() as classes:
        subprocess.run([
            java, "-Xmx700m", "-cp", os.pathsep.join(compiler + libraries),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-cp", os.pathsep.join(libraries), "-d", classes,
            SERVER_SRC / "main" / PACKAGE / "CourseEssayFramework.kt",
            SERVER_SRC / "test" / PACKAGE / "CourseEssayEvidenceTest.kt",
            HERE / "Probe.kt",
        ], check=True)
        launcher = [java, "-cp", os.pathsep.join([classes, *libraries])]
        subprocess.run([*launcher, "com.coach.server.ProbeKt"], check=True,
                       input=json.dumps(cases).encode("ascii"))
        subprocess.run([*launcher, "org.junit.runner.JUnitCore", "com.coach.server.CourseEssayEvidenceTest"],
                       check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", type=Path, required=True, help="Local 995-page OpenStax PDF")
    parser.add_argument("--gradle-home", type=Path, default=os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"),
                        help="Gradle user home containing the cached project dependencies")
    args = parser.parse_args()
    try:
        run(args.pdf, args.gradle_home)
    except subprocess.CalledProcessError as exc:
        sys.exit(exc.returncode)
