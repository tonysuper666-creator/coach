import http.server, ipaddress, json, os, pathlib, plistlib, re, secrets as random_secrets, select, socket, socketserver, subprocess, sys, tempfile, threading, time, urllib.request

root = pathlib.Path.cwd()
server = root / 'server/app/build/install/app/bin/app'
hosts = re.findall(r'\binet (\d+\.\d+\.\d+\.\d+)', subprocess.check_output(['ifconfig'], text=True))
host = next((v for v in hosts if ipaddress.ip_address(v) in ipaddress.ip_network('100.64.0.0/10')), None)
if not host: sys.exit('No Tailscale IPv4 interface')
secrets = [host]
def report(text):
    for secret in sorted(secrets, key=len, reverse=True):
        if secret: text = text.replace(secret, '[redacted]')
    for line in text.splitlines():
        if any(x in line for x in ['error:', 'Test Case', 'Test Suite', 'Executed ', 'elapsed:', 'Revoked HTTP:', 'Revoked WS handshake:', '** TEST', '** BUILD', 'Testing failed', 'failed to', 'error=']): print(line, flush=True)
def run(args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    report(result.stdout)
    print(args[0], args[-1], 'exit', result.returncode, flush=True)
    if result.returncode: raise RuntimeError('command failed')
    return result.stdout.strip()
def freeport():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); return s.getsockname()[1]
# An authenticated test-only control toggles a byte-transparent, disposable TCP
# relay. Dropping it closes actual simulator HTTP/WS connections, without changing
# the host network or exposing a test control in the application.
class Relay(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True
    def __init__(self, address, target):
        self.target = target
        self.lock = threading.Lock()
        self.active = set()
        self.enabled = True
        super().__init__(address, Forward)
    def set_enabled(self, enabled):
        with self.lock:
            self.enabled = enabled
            if not enabled:
                for item in list(self.active):
                    try: item.shutdown(socket.SHUT_RDWR)
                    except OSError: pass
                    item.close()
class Forward(socketserver.BaseRequestHandler):
    def handle(self):
        with self.server.lock:
            if not self.server.enabled: return
            try: upstream = socket.create_connection(self.server.target, timeout=3)
            except OSError: return
            self.server.active.update((self.request, upstream))
        try:
            while True:
                ready, _, _ = select.select([self.request, upstream], [], [], 1)
                for source in ready:
                    data = source.recv(65536)
                    if not data: return
                    (upstream if source is self.request else self.request).sendall(data)
        except (OSError, ValueError): pass
        finally:
            with self.server.lock:
                self.server.active.difference_update((self.request, upstream))
            upstream.close()
class Control(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        if self.headers.get('X-Test-Control') != self.server.secret or self.path not in ('/down', '/up'):
            self.send_response(403); self.end_headers(); return
        self.server.relay.set_enabled(self.path == '/up')
        self.send_response(204); self.end_headers()
    def log_message(self, *args): pass

proc = None
sim = None
relay = None
control = None
relay_started = control_started = False
with tempfile.TemporaryDirectory(prefix='coach-ios-isolated-') as tmp:
    tmp = pathlib.Path(tmp)
    try:
        config = tmp / 'local.xcconfig'; config.write_text('COACH_ATS_HOST = ' + host + '\n')
        derived = tmp / 'derived'
        common = ['-project', 'ios/CoachApp.xcodeproj', '-scheme', 'CoachAppIntegration', '-derivedDataPath', str(derived), '-xcconfig', str(config), 'CODE_SIGNING_ALLOWED=YES', 'CODE_SIGN_IDENTITY=-']
        run(['xcodebuild'] + common + ['-destination', 'generic/platform=iOS Simulator', 'build-for-testing'])
        products = derived / 'Build/Products/Debug-iphonesimulator'
        with (products / 'CoachApp.app/Info.plist').open('rb') as f: info=plistlib.load(f)
        assert host in info['NSAppTransportSecurity']['NSExceptionDomains']
        print('ATS specific host configured: yes', flush=True)
        sim = run(['xcrun', 'simctl', 'create', 'TON1411-isolated', 'com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro', 'com.apple.CoreSimulator.SimRuntime.iOS-27-0']).splitlines()[-1]
        run(['xcrun', 'simctl', 'boot', sim]); run(['xcrun', 'simctl', 'bootstatus', sim, '-b'])
        port=freeport(); bridgeport=freeport()
        if port==bridgeport or port in (18791,8791,8790,3790) or bridgeport in (18791,8791,8790,3790): raise RuntimeError('unsafe port')
        env=os.environ.copy()
        for name in list(env):
            if name.startswith('COACH_'): del env[name]
        env.update({'COACH_MANAGE_SERVICES':'0', 'COACH_TRAFFIC':'test', 'COACH_BRIDGE_URL':f'http://127.0.0.1:{bridgeport}', 'COACH_PORT':str(port), 'COACH_TAIL_HOST':host, 'COACH_SOURCE_LOOKUP':'false', 'JAVA_TOOL_OPTIONS':f'-Duser.home={tmp}'})
        for key in ['DATA','STATE','LOG','GENERATED','MOCK']:
            path=tmp/key.lower(); path.mkdir(); env[f'COACH_{key}_DIR']=str(path)
        pack = tmp/'mock'/'mcq-mainline'; pack.mkdir()
        (pack/'W01.json').write_bytes((root/'ios/CoachAppIntegrationTests/Synthetic-W01.json').read_bytes())
        tokenfile=tmp/'token'; env['COACH_TOKEN_FILE']=str(tokenfile)
        proc=subprocess.Popen([str(server)], cwd=root, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        token=None
        for _ in range(100):
            if proc.poll() is not None: raise RuntimeError('isolated server exited')
            if tokenfile.exists():
                token=tokenfile.read_text().strip()
                try:
                    request=urllib.request.Request(f'http://127.0.0.1:{port}/v1/hello', headers={'Authorization':'Bearer '+token,'X-Coach-Device':'desktop'})
                    with opener.open(request, timeout=1) as response: response.read()
                    break
                except Exception: pass
            time.sleep(.2)
        else: raise RuntimeError('isolated server readiness failed')
        secrets.append(token)
        relay = Relay((host, 0), ('127.0.0.1', port))
        control = http.server.ThreadingHTTPServer((host, 0), Control)
        if relay.server_address[1] in (18791,8791,8790,3790) or control.server_address[1] in (18791,8791,8790,3790): raise RuntimeError('unsafe relay port')
        control.secret = random_secrets.token_urlsafe(32); control.relay = relay
        secrets.append(control.secret)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        relay_started = True
        threading.Thread(target=control.serve_forever, daemon=True).start()
        control_started = True
        req=urllib.request.Request(f'http://127.0.0.1:{port}/v1/pairing-code', data=b'', method='POST', headers={'Authorization':'Bearer '+token,'X-Coach-Device':'desktop'})
        with opener.open(req, timeout=3) as response: code=json.load(response)['code']
        secrets.append(code)
        bundles=list(products.glob('**/CoachAppIntegrationTests.xctest'))
        if len(bundles)!=1: raise RuntimeError('unexpected test bundle count: '+str(len(bundles)))
        with (bundles[0]/'IsolatedFixture.plist').open('wb') as f: plistlib.dump({'base':f'http://{host}:{relay.server_address[1]}', 'code':code,'adminToken':token, 'control':f'http://{host}:{control.server_address[1]}', 'controlSecret':control.secret},f)
        run(['codesign', '-f', '-s', '-', str(bundles[0])])

        for test in ['testRealtimeOfflineAndRetry', 'testRealtimeRestoreAndRevoke']:
            run(['xcodebuild']+common+['-destination', 'id='+sim, '-only-testing:CoachAppIntegrationTests/RealConnectionTests/'+test, 'test-without-building'])
        screenshot = os.environ.get('COACH_IOS_SCREENSHOT')
        if screenshot:
            run(['xcrun', 'simctl', 'launch', sim, 'dev.coach.ton1411.simulator'])
            time.sleep(3)
            run(['xcrun', 'simctl', 'io', sim, 'screenshot', screenshot])
    finally:
        if control:
            if control_started: control.shutdown()
            control.server_close()
        if relay:
            relay.set_enabled(False)
            if relay_started: relay.shutdown()
            relay.server_close()
        if proc:
            proc.terminate()
            try: proc.wait(timeout=10)
            except subprocess.TimeoutExpired: proc.kill(); proc.wait()
        if sim:
            subprocess.run(['xcrun','simctl','shutdown',sim],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            subprocess.run(['xcrun','simctl','delete',sim],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
