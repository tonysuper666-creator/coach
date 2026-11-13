# Generated content validation

Use Python 3 with no third-party dependencies:

```sh
python3 tools/content/validate.py generated-content/ --exam-dir private-exams/ --report local-report.md
cd tools/content && python3 -m unittest
```

The exam directory must contain UTF-8 `.md` or `.txt` files; all such files are indexed recursively. Only an independently authorized reviewer may run a comparison against private exam material; the author must not read that material or the index. The index and matching phrases are never written to the report. Matching uses case-insensitive sequences of eight English letter-only words, with punctuation and digits separating words, and sequences of 15 consecutive Han characters (punctuation, whitespace and other characters break a Han sequence). Each JSON string is compared independently; other supported text files are compared as text. This is a literal lexical check: it cannot detect paraphrases, disguised copies, or overlaps interrupted by punctuation; independent semantic review remains necessary. The report includes input filenames and must remain private if those names are sensitive.

Paper/pack JSON is checked against the generated-content format. Papers must declare `total_marks`; where a section permits a choice, unequal question marks make its total ambiguous. Numeric answers with a non-null tolerance must be finite decimal numbers; other analytical answers may be text. Directory reports distinguish format plus overlap checks from overlap-only files and unsupported files. An empty or unreadable exam source fails rather than silently passing. Exit codes: 0 means all files checked without issues, 1 means problems or unchecked files, 2 means the run could not be performed.
