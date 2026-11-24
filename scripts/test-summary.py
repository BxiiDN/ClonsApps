#!/usr/bin/env python3
"""Ringkas hasil JUnit XML (JVM) menjadi Markdown."""
import glob, sys, xml.etree.ElementTree as ET

def summarize(title, pattern):
    t = f = e = s = 0
    failed = []
    for p in sorted(glob.glob(pattern)):
        r = ET.parse(p).getroot()
        suites = [r] if r.tag == "testsuite" else r.findall("testsuite")
        for su in suites:
            t += int(su.get("tests", 0)); f += int(su.get("failures", 0)); e += int(su.get("errors", 0)); s += int(su.get("skipped", 0))
            for tc in su.findall("testcase"):
                if tc.find("failure") is not None or tc.find("error") is not None:
                    failed.append(f"{su.get('name')}.{tc.get('name')}")
    status = "lulus" if t and not f and not e else ("tidak dijalankan" if not t else "GAGAL")
    lines = [f"- **{title}**: {t} test, {f} gagal, {e} error, {s} dilewati → {status}"]
    lines += [f"  - gagal: `{x}`" for x in failed]
    return lines

out = ["## Pengujian JVM", ""]
out += summarize("Mesin cloning (:engine, termasuk APK contoh nyata)", "engine/build/test-results/test/*.xml")
out += summarize("Aplikasi (:app unit test)", "app/build/test-results/testReleaseUnitTest/*.xml")
text = "\n".join(out) + "\n"
print(text)
if len(sys.argv) > 1:
    open(sys.argv[1], "w").write(text)
