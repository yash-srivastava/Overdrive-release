# HTTP body compatibility checks

Run `sh tools/tests/http-body-compatibility/run.sh JDK_DIRECTORY JSON_JAR OUTPUT_DIRECTORY`
with a local JDK, Python3, an existing org.json jar and a disposable output directory.
The runner does not fetch dependencies. It compiles verbatim production methods
and fixed dummy collaborators. See `.github/tdd/http-body-initial-capacity.md` for
scope and evidence. These functional checks do not measure resource allocation.

The fixture includes the production `HttpRequestHead.java` byte-framing helper
so the extracted daemon handler exercises the same bounded head reader and
exact `Content-Length` body decoding as the app.
