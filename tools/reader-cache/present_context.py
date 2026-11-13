"""Owned-emulator context used by the five-part first-present input contract."""
import hashlib
import re
import subprocess


def parse_geometry(size,density,font):
    dimensions=re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)',size)
    densities=re.findall(r'(?:Physical|Override) density: (\d+)',density)
    if not dimensions or not densities:raise ValueError('missing_emulator_geometry')
    width,height=map(int,dimensions[-1])
    scale=float(font.strip())
    return dict(width=width,height=height,densityDpi=int(densities[-1]),fontScale=scale)


def capture_context(adb):
    def read(*args):return subprocess.check_output(adb+['shell',*args],text=True).strip()
    if read('getprop','ro.kernel.qemu')!='1':raise ValueError('only_owned_emulator_context')
    avd=subprocess.check_output(adb+['emu','avd','name'],text=True).splitlines()
    avd=[name.strip() for name in avd if name.strip() and name.strip()!='OK']
    if len(avd)!=1:raise ValueError('ambiguous_avd_identity')
    rendering=parse_geometry(read('wm','size'),read('wm','density'),read('settings','get','system','font_scale'))
    if rendering!=dict(width=2208,height=1840,densityDpi=420,fontScale=1.0):raise ValueError('non_contract_geometry')
    api=int(read('getprop','ro.build.version.sdk'))
    abi=read('getprop','ro.product.cpu.abi')
    if api!=35 or abi!='arm64-v8a':raise ValueError('non_contract_sdk_or_abi')
    return dict(device=dict(avd=avd[0],model=read('getprop','ro.product.model'),abi=abi),
        sdk=dict(api=api,
                 fingerprintSha256=hashlib.sha256(read('getprop','ro.build.fingerprint').encode()).hexdigest()),rendering=rendering)
