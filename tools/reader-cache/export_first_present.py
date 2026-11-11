"""Two offline queries using the already cached v59.1 binary; no wrapper/download."""
import argparse
import csv
import json
from pathlib import Path
import subprocess
from first_present import digest,load,analyze,TP_SHA256


def export(directory,binary):
    directory=Path(directory)
    binary=Path(binary).expanduser()
    if not binary.is_file() or digest(binary.read_bytes())!=TP_SHA256:raise ValueError('missing_or_wrong_cached_trace_processor')
    identity_path=directory/'run.json'
    run=json.loads(identity_path.read_text())
    report_path=directory/'first-present-report.json'
    if report_path.exists():raise ValueError('refusing_to_overwrite_existing_export')
    pid=run.get('measurementPid')
    errors=[]
    if pid is None:errors.append('missing_measurement_pid')
    elif type(pid) is not int or pid<=0:errors.append('invalid_measurement_pid')
    if run.get('measurementContract',{}).get('origin')=='android_capture' and (
            run.get('commandExit')!=0 or run.get('instrumentationPassed') is not True):
        errors.append('native_test_failed_or_missing_pass_receipt')
    if errors:
        report=dict(complete=False,errors=errors,phase='export_preflight',queriesExecuted=0,
                    measurementPid=pid,measurementPidError=run.get('measurementPidError'),samples=[],
                    firstPresentLatencyVerified=False,matchedTupleContract=False,matchedBaselineObserved=False,
                    physicalMemoryBudgetVerified=False,gpuReleaseVerified=False)
        report_path.write_text(json.dumps(report,indent=2)+'\n')
        return report
    raw=directory/'frames.perfetto-trace'
    sql=Path(__file__).with_name('first_present.sql').read_text().replace('{{PID}}',str(pid))
    queries={'first-present':sql,'first-present-quality':'SELECT name,value,severity,source,idx FROM stats WHERE value>0;'}
    receipts=[]
    for name,text in queries.items():
        query=directory/(name+'.sql')
        destination=directory/(name+'.csv')
        if query.exists() or destination.exists():raise ValueError('refusing_to_overwrite_existing_export')
        query.write_text(text)
        command=[str(binary),'query','--no-agent','--no-progress','--format','csv','-f',str(query),str(raw)]
        with destination.open('w') as out,(directory/(name+'.log')).open('w') as err:
            result=subprocess.run(command,stdout=out,stderr=err)
        receipts.append(dict(command=command,exit=result.returncode))
        (directory/'first-present-query-receipts.json').write_text(json.dumps(receipts,indent=2)+'\n')
        if result.returncode:raise RuntimeError('query_failed_keep_raw_outputs')
    with (directory/'first-present-quality.csv').open(newline='') as stream:quality=list(csv.DictReader(stream))
    run.update(rawTraceSha256=digest(raw.read_bytes()),framesCsvSha256=digest((directory/'first-present.csv').read_bytes()),
               frameQuerySha256=digest(sql.encode()),traceProcessorBinarySha256=TP_SHA256,traceQualityIssues=quality)
    identity_path.write_text(json.dumps(run,indent=2)+'\n')
    report=analyze(*load(directory))
    report_path.write_text(json.dumps(report,indent=2)+'\n')
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    parser.add_argument('--trace-processor',type=Path,required=True)
    args=parser.parse_args()
    result=export(args.directory,args.trace_processor)
    raise SystemExit(0 if result['complete'] else 1)
