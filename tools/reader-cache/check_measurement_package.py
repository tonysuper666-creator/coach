"""Execute the actual Android-build package gate on a host JVM; never claim native execution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[2]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    output=args.output.resolve()
    output.relative_to(ROOT)
    output.mkdir(parents=True,exist_ok=False)
    suffix='.exe' if os.name=='nt' else ''
    def binary(name):return str(args.java_home/'bin'/(name+suffix))
    candidates=list((ROOT/'androidApp/build/intermediates/javac/debugAndroidTest').rglob('ReaderMeasurementPackage.class'))
    if len(candidates)!=1:raise RuntimeError('Build the fixed-head Android test APK first; expected one guard class')
    guard=candidates[0]
    classes=guard.parents[3]
    harness=Path(__file__).with_name('jvm')/'ReaderMeasurementPackageCheck.java'
    compile_command=[binary('javac'),'-J-Xmx128m','--release','17','-cp',str(classes),'-d',str(output),str(harness)]
    compiled=subprocess.run(compile_command,capture_output=True,text=True)
    (output/'javac.log').write_text(compiled.stdout+compiled.stderr)
    if compiled.returncode:raise RuntimeError('Host guard-check harness failed to compile')
    # Place the Android-build class directory first: the checker never compiles or substitutes the guard.
    run_command=[binary('java'),'-Xmx128m','-ea','-cp',os.pathsep.join((str(classes),str(output))),
                 'com.coach.android.ReaderMeasurementPackageCheck']
    checked=subprocess.run(run_command,capture_output=True,text=True)
    (output/'guard-check.log').write_text(checked.stdout+checked.stderr)
    if checked.returncode or 'CHECKS 10' not in checked.stdout:raise RuntimeError('Compiled package guard regression failed')
    kotlin=ROOT/'androidApp/build/intermediates/built_in_kotlinc/debugAndroidTest/compileDebugAndroidTestKotlin/classes'
    bindings={}
    for name in ('ReaderPageObservedSmoke','ReaderPageCachePathSmoke','ReaderPageLifetimeSmoke',
                 'ReaderImageTransportSmoke','ReaderMeasurementPackageSmoke'):
        class_name='com.coach.android.'+name
        if name=='ReaderImageTransportSmoke':class_name+='$separatesHeadersBodyAndSyntheticServerTime$1'
        disassembled=subprocess.run([binary('javap'),'-J-Xmx128m','-classpath',str(kotlin),'-c','-p',class_name],capture_output=True,text=True)
        (output/(name+'-bytecode.txt')).write_text(disassembled.stdout+disassembled.stderr)
        if disassembled.returncode or 'ReaderMeasurementPackage.requireIsolated:' not in disassembled.stdout:
            raise RuntimeError('Missing compiled shared-gate call in '+name)
        bindings[name]=class_name
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip())
    if dirty:raise RuntimeError('Clean fixed head required for a delivery receipt')
    report=dict(sourceHead=head,sourceDirty=False,hostGuardChecks=10,allowedPackages=2,rejectedPackages=8,
        guardClass=str(guard.relative_to(ROOT)),guardClassSha256=hashlib.sha256(guard.read_bytes()).hexdigest(),
        compileExit=compiled.returncode,guardExecutionExit=checked.returncode,compiledEntryBindings=bindings,
        scope='actual Android-build guard class executed on a host JVM and compiled entry calls; no Android instrumentation',
        nativeEntryReachedVerified=False,firstPresentLatencyVerified=False,physicalMemoryBudgetVerified=False,
        compileCommand=compile_command,runCommand=run_command)
    (output/'guard-check.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))


if __name__=='__main__':main()
