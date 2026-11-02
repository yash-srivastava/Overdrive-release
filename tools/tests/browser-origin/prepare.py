#!/usr/bin/env python3
"""Compile-boundary fixture: retain auth decisions and HTTP header plumbing verbatim."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys


def extract(text, name):
    masked = re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                    lambda match: ' ' * len(match[0]), text)
    matches = list(re.finditer(r'^\s*(?:(?:public|private|protected|static|final|synchronized)\s+)+'
                              r'[\w.<>\[\]]+\s+' + re.escape(name) + r'\s*\(', masked, re.M))
    if len(matches) != 1:
        raise ValueError('Expected one production method: ' + name)
    start = matches[0].start()
    if masked[start] == '\n':
        start = masked.index('\n', start) + 1
    brace = masked.index('{', matches[0].end())
    depth, end = 1, brace + 1
    while depth:
        depth += (masked[end] == '{') - (masked[end] == '}')
        end += 1
    return text[start:end]


def main():
    repo, output = map(Path, sys.argv[1:])
    production = repo / 'app/src/main/java/com/overdrive/app/server'
    fixture = Path(__file__).resolve().parent / 'src'
    output.mkdir(parents=True, exist_ok=True)
    for source in fixture.rglob('*.java'):
        target = output / source.relative_to(fixture)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    methods = ['handleClient', 'redactQueryToken', 'maxBodyBytesForRequestLine',
               'proxyProtocolV1Source', 'isValidProxyAddress', 'isValidProxyPort',
               'forwardedForValue', 'isCustomWidgetsPath', 'isCommunityWidgetsPath']
    text = (production / 'HttpServer.java').read_text()
    retained = [extract(text, method) for method in methods]
    provenance = [{'file': 'HttpServer.java', 'method': method,
                   'sha256': hashlib.sha256(body.encode()).hexdigest(), 'verbatim': True}
                  for method, body in zip(methods, retained)]
    wrapper = '''package com.overdrive.app.server;
import java.io.*; import java.net.*; import org.json.JSONObject;
import com.overdrive.app.daemon.CameraDaemon;
public class HttpServer {
    private static final ThreadLocal<CallerContext> CALLER = new ThreadLocal<>();
    private static class CallerContext { CallerContext(boolean t,String c,String a) {} }
    void runFixture(Socket client) { handleClient(client); }
    boolean serveStaticFile(OutputStream out,String path,String... extra) {
        throw new AssertionError("Unexpected static route: " + path);
    }
    boolean routeToHandlers(String method,String path,String body,String range,String etag,
                            OutputStream out) throws Exception {
        HttpResponse.sendJson(out,"{\\"dummySentinel\\":true}");
        return true;
    }
    void handleWebSocketUpgrade(Socket client,String key,boolean broadway,boolean tunnel) {
        Admission.websocket = "video";
    }
'''
    target = output / 'com/overdrive/app/server/HttpServer.java'
    target.write_text(wrapper + '\n'.join(retained) + '\n}\n')
    for name in ['AuthMiddleware', 'HttpResponse', 'GenAiRequestSecurity']:
        original = production / (name + '.java')
        shutil.copyfile(original, output / 'com/overdrive/app/server' / original.name)
        provenance.append({'file': original.name, 'whole_file': True, 'verbatim': True,
                           'sha256': hashlib.sha256(original.read_bytes()).hexdigest()})
    metadata = {'production': provenance,
                'replacements': ['socket transport', 'router sentinel', 'AuthManager dummy validator',
                                 'hardware/UI/websocket endpoints'],
                'no_real_jwt_or_vehicle_actions': True}
    (output.parent / 'fixture-provenance.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print('SOURCE_PROVENANCE ' + json.dumps(metadata, separators=(',', ':')))


if __name__ == '__main__':
    main()
