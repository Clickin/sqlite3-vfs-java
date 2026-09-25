import json, os, pathlib, queue, signal, subprocess, sys, threading, time
cfg=json.loads(pathlib.Path(sys.argv[1]).read_text())
root=pathlib.Path(cfg['root'])
ssh=cfg['ssh']
results=[]
def remote(command, timeout=90):
    p=subprocess.run(ssh+[command],capture_output=True,text=True,timeout=timeout)
    if p.returncode: raise RuntimeError(p.stdout+p.stderr)
    return p.stdout

def boot():
    p=subprocess.Popen(cfg['qemu'],stdout=open(root/'qemu.log','ab'),stderr=subprocess.STDOUT)
    deadline=time.monotonic()+120
    while time.monotonic()<deadline:
        # Bounded service readiness, not workload timing or crash scheduling.
        r=subprocess.run(ssh+['true'],capture_output=True,text=True,timeout=8)
        if r.returncode==0: return p
        if p.poll() is not None: raise RuntimeError('QEMU exited during boot')
        time.sleep(0.5)
    p.kill();p.wait();raise TimeoutError('Guest SSH readiness')

java="./jdk/bin/java --illegal-native-access=deny -cp '.:lib/*' VmPowerCutPeer"
cases=[('DELETE','before-journal-sync'),('DELETE','after-journal-sync'),('DELETE','after-db-write'),('DELETE','after-commit'),('WAL','before-commit'),('WAL','after-commit')]
vm=None
try:
    vm=boot()
    remote('sudo mount /dev/vdb /mnt/vfs')
    for mode,point in cases:
        name=str(time.time_ns())+'-'+mode+'-'+point
        db='/mnt/vfs/'+name+'.db'
        entry={'mode':mode,'point':point,'acknowledged':[]}
        out=remote(f'{java} {db} {mode} init')
        assert 'ACK 1' in out,out
        entry['acknowledged'].append(1)
        with open(root/'host-acks.jsonl','a') as log:
            log.write(json.dumps({'case':name,'ack':1})+'\n');log.flush();os.fsync(log.fileno())
        guest=subprocess.Popen(ssh+[f'{java} {db} {mode} {point}'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        lines=queue.Queue()
        def collect():
            for line in guest.stdout: lines.put(line)
            lines.put(None)
        threading.Thread(target=collect,daemon=True).start()
        deadline=time.monotonic()+60
        captured=[]
        while True:
            line=lines.get(timeout=max(.1,deadline-time.monotonic()))
            if line is None: raise RuntimeError('Guest exited without HOLD: '+''.join(captured))
            captured.append(line)
            if line.startswith('ACK '):
                ack=int(line.split()[1]);entry['acknowledged'].append(ack)
                with open(root/'host-acks.jsonl','a') as log:
                    log.write(json.dumps({'case':name,'ack':ack})+'\n');log.flush();os.fsync(log.fileno())
            if line.strip()=='HOLD '+point: break
        # SIGKILL destroys the entire guest, with no shutdown or QEMU flush/cleanup.
        vm.kill();entry['qemu_exit']=vm.wait(timeout=10);vm=None
        assert entry['qemu_exit']==-signal.SIGKILL
        guest.wait(timeout=15)
        (root/(name+'-guest.log')).write_text(''.join(captured))
        vm=boot()
        remote('sudo mount /dev/vdb /mnt/vfs')
        committed=2 in entry['acknowledged']
        # Alternate first recovery implementation; both inspect the same real file.
        native="import sqlite3; c=sqlite3.connect("+repr(db)+"); row=c.execute('select count(*),sum(value),sum(length(payload)),sum(case when id<=64 then value else 0 end) from ledger').fetchone(); expected="+repr((128,192,2097152,128) if committed else (64,64,1048576,64))+"; assert row==expected,(row,expected); assert c.execute('pragma integrity_check').fetchone()==('ok',); print('NATIVE VERIFIED',row,'integrity=ok'); c.close()"
        import shlex
        cmds=[f'{java} {db} {mode} verify {str(committed).lower()}', 'python3 -c '+shlex.quote(native)]
        if len(results)%2: cmds.reverse()
        entry['verification']=[remote(cmd).strip() for cmd in cmds]
        entry['recovery_order']='native-first' if len(results)%2 else 'jvm-first'
        results.append(entry)
        (root/'results.json').write_text(json.dumps(results,indent=2))
        print(json.dumps(entry),flush=True)
    remote('sudo poweroff',timeout=30)
    vm.wait(timeout=30);vm=None
finally:
    if vm is not None:
        vm.kill();vm.wait(timeout=10)
