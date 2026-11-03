#!/usr/bin/env python3
"""Exercise the unchanged static HTTP source branch against a built canonical APK.

This is a source-excerpt fixture, not an installed daemon/authentication test.
Model-cache and catalog fallbacks are stubbed; every tested shared asset exists.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def method(source, marker):
    start = source.index(marker)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--json-jar', required=True, type=Path)
    parser.add_argument('--scratch', required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    source = (root / 'app/src/main/java/com/overdrive/app/server/HttpServer.java').read_text()
    static = method(source, '    private boolean serveStaticFile(OutputStream out, String relativePath, String ifNoneMatch)')
    mime = method(source, '    private String getContentType(String path)')
    route_start = source.index('                String filePath = path.substring(1);', source.index('path.startsWith("/shared/")'))
    route_end = source.index('            } else if (path.startsWith("/h264/"))', route_start)
    route = source[route_start:route_end]
    work = args.scratch
    work.mkdir(parents=True, exist_ok=False)
    web = work / 'web'
    names = []
    with zipfile.ZipFile(args.apk) as package:
        for item in package.infolist():
            if not item.filename.startswith('assets/web/shared/') or item.is_dir():
                continue
            relative = item.filename[len('assets/web/'):]
            if '..' in Path(relative).parts or item.file_size > 33554432:
                raise AssertionError('unsafe or oversized asset fixture')
            destination = web / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(package.read(item))
            names.append(relative)
        packaged_shared_count = len(names)
        # Shared web assets currently use system fonts. Exercise the same
        # alias branch with real APK font bytes under a fixture-only URL;
        # do not claim that this URL is shipped by the application.
        fixture_font = package.read('assets/glass/barlow-500.ttf')
        (web / 'shared/__fixture-font.ttf').write_bytes(fixture_font)
        names.append('shared/__fixture-font.ttf')
    if not names or any('\n' in name for name in names):
        raise AssertionError('invalid shared asset inventory')
    (work / 'files.txt').write_text('\n'.join(sorted(names)) + '\n')
    java = '''package com.overdrive.app.server;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class PackagedAssetRouteProbe {
 private final String WEB_ROOT;
 private PackagedAssetRouteProbe(String root) { WEB_ROOT=root; }
 private static class CameraDaemon { static void log(String message) {} }
 private static class ModelsApiHandler { static File cachedModelFile(String name) { return null; } }
 private boolean serveAssetFallback(OutputStream out, String path, String tag) { return false; }
 byte[] response(String path, String ifNoneMatchHeader, String rangeHeader) throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  if (path.startsWith("/shared/") || path.startsWith("/local/")) {
''' + route + '''
  } else { HttpResponse.sendError(out,404,"Not Found"); }
  return out.toByteArray();
 }
''' + static + '\n' + mime + '''
 private static byte[] body(byte[] response) {
  for(int i=0;i+3<response.length;i++)
   if(response[i]==13 && response[i+1]==10 && response[i+2]==13 && response[i+3]==10)
    return Arrays.copyOfRange(response,i+4,response.length);
  throw new AssertionError("missing HTTP separator");
 }
 private static String headers(byte[] response) {
  String all=new String(response,StandardCharsets.ISO_8859_1);
  return all.substring(0,all.indexOf("\\r\\n\\r\\n"));
 }
 private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
 public static void main(String[] args) throws Exception {
  PackagedAssetRouteProbe server=new PackagedAssetRouteProbe(args[0]);
  int css=0,fonts=0,count=0;
  for(String relative:Files.readAllLines(Paths.get(args[1]),StandardCharsets.UTF_8)) {
   byte[] canonical=server.response("/"+relative+"?v=qa#fragment",null,null);
   byte[] alias=server.response("/local/"+relative+"?v=qa#fragment",null,null);
   byte[] expected=Files.readAllBytes(Paths.get(args[0]).resolve(relative));
   require(headers(canonical).startsWith("HTTP/1.1 200 "),relative+" canonical status");
   require(Arrays.equals(canonical,alias),relative+" alias response changed");
   require(Arrays.equals(expected,body(alias)),relative+" bytes changed");
   if(relative.endsWith(".css"))css++;
   if(relative.endsWith(".woff")||relative.endsWith(".woff2")||relative.endsWith(".ttf"))fonts++;
   count++;
  }
  require(css>0 && fonts>0,"CSS/font assets not exercised");
  byte[] first=server.response("/shared/performance.js?v=qa",null,null);
  String etag=null;
  for(String row:headers(first).split("\\r\\n"))if(row.startsWith("ETag: "))etag=row.substring(6);
  require(etag!=null,"ETag missing");
  byte[] cached=server.response("/local/shared/performance.js?v=qa",etag,null);
  require(headers(cached).startsWith("HTTP/1.1 304 ") && body(cached).length==0,"alias revalidation changed");
  byte[] ranged=server.response("/local/shared/performance.js?v=qa",null,"bytes=0-3");
  require(Arrays.equals(first,ranged),"existing static Range behavior changed");
  require(headers(server.response("/local/shared/not-present.js",null,null)).startsWith("HTTP/1.1 404 "),"missing asset accepted");
  require(headers(server.response("/local/shared/../../outside",null,null)).startsWith("HTTP/1.1 404 "),"traversal accepted");
  System.out.println("ROUTE_CONTROLS shared="+count+" css="+css+" fonts="+fonts+" queryFragment=PASS etag304=PASS staticRangeFull200=PASS missing404=PASS traversal404=PASS");
 }
}
'''
    java_path = work / 'PackagedAssetRouteProbe.java'
    java_path.write_text(java)
    classes = work / 'classes'
    classes.mkdir()
    response_source = root / 'app/src/main/java/com/overdrive/app/server/HttpResponse.java'
    subprocess.run(['javac', '-cp', str(args.json_jar), '-d', str(classes), str(response_source), str(java_path)], check=True)
    subprocess.run(['java', '-cp', str(classes) + ':' + str(args.json_jar),
                    'com.overdrive.app.server.PackagedAssetRouteProbe', str(web), str(work / 'files.txt')], check=True)
    print(json.dumps({'scope': 'unchanged static route source excerpt; no auth, catalog/model fallback or installed Android acceptance',
                      'packaged_shared_assets': packaged_shared_count, 'fixture_font_only': 'shared/__fixture-font.ttf', 'source_excerpts_sha256': hashlib.sha256((route + static + mime).encode()).hexdigest(),
                      'http_response_sha256': hashlib.sha256(response_source.read_bytes()).hexdigest()}))


if __name__ == '__main__':
    main()
