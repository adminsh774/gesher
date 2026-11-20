#!/usr/bin/env python3
"""Explicit old-head negative suppliers, independently pinned to the completed107 run.

These archives are never represented as artifacts of the diagnostic or a repair head.
"""
import argparse, hashlib, json, os, pathlib, urllib.request, urllib.error, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
API = 'https://api.github.com/repos/gesher-runtime/gesher'
MANIFEST = json.loads((pathlib.Path(__file__).with_name('old-artifacts.json')).read_text())

def request(path):
    return urllib.request.Request(API+path, headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'], 'Accept':'application/vnd.github+json', 'X-GitHub-Api-Version':'2022-11-28'})
def read(path):
    with urllib.request.urlopen(request(path), timeout=90) as r: return json.load(r)
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl): return None

def fetch(args):
    m=MANIFEST; run=read('/actions/runs/'+str(m['run']))
    if (run['head_sha'],run['run_attempt'],run['status']) != (m['source'],m['attempt'],'completed'): raise RuntimeError('Old diagnostic source/run changed')
    for sha in (m['source'], m['checkout']):
        if read('/git/commits/'+sha)['tree']['sha'] != m['tree']: raise RuntimeError('Old checkout is not the source tree')
    specs=[x for x in m['artifacts'] if x['name'] in args.names]
    if len(specs) != len(set(args.names)): raise RuntimeError('Unpinned supplier requested')
    args.out.mkdir(parents=True, exist_ok=True)
    for s in specs:
        job=read('/actions/jobs/'+str(s['job'])); a=read('/actions/artifacts/'+str(s['id']))
        if (job['run_id'],job['run_attempt'],job['head_sha'],job['name'],job['conclusion']) != (m['run'],m['attempt'],m['source'],s['producer'],'success'): raise RuntimeError('Producer mismatch')
        if (a['name'],a['digest'],a['workflow_run']['id'],a['workflow_run']['head_sha'],a['expired']) != (s['name'],s['digest'],m['run'],m['source'],False): raise RuntimeError('Archive identity mismatch')
        if not job['started_at'] <= a['created_at'] <= job['completed_at']: raise RuntimeError('Archive not created by pinned producer interval')
        destination=args.out/s['name']; destination.mkdir()
        archive=args.out/(s['name']+'.zip')
        try:
            response=urllib.request.build_opener(NoRedirect).open(request('/actions/artifacts/'+str(s['id'])+'/zip'),timeout=90)
        except urllib.error.HTTPError as e:
            if e.code != 302: raise
            # The signed archive URL is fetched without forwarding the GitHub credential.
            response=urllib.request.urlopen(e.headers['Location'], timeout=180)
        with response, archive.open('xb') as output:
            while chunk := response.read(1024*1024): output.write(chunk)
        digest='sha256:'+hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest != s['digest']: raise RuntimeError('Downloaded archive digest mismatch')
        with zipfile.ZipFile(archive) as z:
            for name in z.namelist():
                p=pathlib.PurePosixPath(name)
                if p.is_absolute() or '..' in p.parts or '\\' in name: raise RuntimeError('Unsafe archive path')
            z.extractall(destination)
        receipt={'purpose':'unchanged107 negative only','source':m['source'],'checkout':m['checkout'],'tree':m['tree'],'run':m['run'],'attempt':m['attempt'],**s}
        (args.out/(s['name']+'-receipt.json')).write_text(json.dumps(receipt,indent=2)+'\n')
        print('OLD107_SUPPLIER '+json.dumps(receipt), flush=True)
    if read('/actions/runs/'+str(m['run']))['run_attempt'] != m['attempt']: raise RuntimeError('Producer was rerun during download')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',required=True,type=pathlib.Path);p.add_argument('names',nargs='+');fetch(p.parse_args())
