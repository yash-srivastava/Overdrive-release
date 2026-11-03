#!/usr/bin/env python3
"""Build a JVM fixture from unchanged production method bodies, not copies of their logic."""
import hashlib
import json
from pathlib import Path
import re
import sys


def extract_method(text, name):
    # Mask literals and comments without moving offsets, then balance real braces.
    masked = re.sub(
        r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
        lambda match: " " * len(match[0]), text,
    )
    matches = list(re.finditer(
        r'^\s*(?:(?:public|private|protected|static|final|synchronized)\s+)+'
        r'[\w.<>\[\]]+\s+' + re.escape(name) + r'\s*\(', masked, re.M,
    ))
    if len(matches) != 1:
        raise ValueError(f"Expected one production method {name}, found {len(matches)}")
    start = matches[0].start()
    brace = masked.index("{", matches[0].end())
    depth, end = 1, brace + 1
    while depth:
        depth += (masked[end] == "{") - (masked[end] == "}")
        end += 1
    return text[start:end]


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: prepare.py REPOSITORY GENERATED_DIRECTORY")
    repo, output = map(Path, sys.argv[1:])
    source = repo / "app/src/main/java/com/overdrive/app/server"
    fixture = Path(__file__).resolve().parent
    records = []

    def write(relative, content):
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content)

    text = (source / "HttpServer.java").read_text()
    names = ["handleClient", "redactQueryToken", "maxBodyBytesForRequestLine",
             "proxyProtocolV1Source", "isValidProxyAddress", "isValidProxyPort",
             "forwardedForValue", "isCustomWidgetsPath", "isCommunityWidgetsPath"]
    methods = []
    for name in names:
        method = extract_method(text, name)
        methods.append(method)
        records.append({"file": "HttpServer.java", "method": name,
                        "sha256": hashlib.sha256(method.encode()).hexdigest(),
                        "verbatim": True})
    header = '''package com.overdrive.app.server;
import java.io.*;import java.net.*;import org.json.JSONObject;
import com.overdrive.app.daemon.CameraDaemon;
public class HttpServer {
 private static final ThreadLocal<CallerContext> CALLER = new ThreadLocal<>();
 private static class CallerContext { CallerContext(boolean tunnel,String cookie,String auth) {} }
 static String routedBody;
 static int routedCalls;
 boolean serveStaticFile(OutputStream out,String path,String... headers) { throw new AssertionError("Unexpected static route"); }
 boolean routeToHandlers(String method,String path,String body,String range,String etag,OutputStream out)throws Exception {
   routedBody=body; routedCalls++; HttpResponse.sendJson(out,"{\\"fixtureAccepted\\":true}"); return true;
 }
 void handleWebSocketUpgrade(Socket socket,String key,boolean decoder,boolean tunnel) { throw new AssertionError("No WebSocket in this test"); }
'''
    write("com/overdrive/app/server/HttpServer.java", header + "\n".join(methods) + "\n}\n")
    for name in ("AuthMiddleware", "HttpResponse", "GenAiRequestSecurity"):
        content = (source / (name + ".java")).read_text()
        write("com/overdrive/app/server/" + name + ".java", content)
        records.append({"file": name + ".java", "whole_file": True,
                        "sha256": hashlib.sha256(content.encode()).hexdigest(),
                        "verbatim": True})
    for relative in ("com/overdrive/app/auth/AuthManager.java",
                     "com/overdrive/app/daemon/CameraDaemon.java",
                     "com/overdrive/app/server/HardwareStubs.java",
                     "com/overdrive/app/server/HttpBodyCompatibilityTest.java"):
        write(relative, (fixture / "src" / relative).read_text())
    provenance = {"production": records,
                  "replacement_collaborators": ["socket transport", "router", "AuthManager", "hardware/UI/static routes"],
                  "runtime": "JVM functional body compatibility; no allocation measurement or Android ART claim"}
    print("SOURCE_PROVENANCE " + json.dumps(provenance, sort_keys=True))


if __name__ == "__main__":
    main()
