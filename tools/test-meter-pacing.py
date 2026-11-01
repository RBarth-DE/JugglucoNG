#!/usr/bin/env python3
"""Run the production glucose-meter reconnect pacing methods against a deterministic scheduler.
Android Bluetooth and the meter protocols are outside this test; openGatt() is counted, not run.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
gatt = (root / 'Common/src/mobile/java/tk/glucodata/GlucoseMeterGatt.java').read_text()
pacing = (root / 'Common/src/main/java/tk/glucodata/glucosemeter/MeterSyncPacing.kt').read_text()


def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
        end += 1
    return source[start:end]


methods = '\n'.join(method(gatt, signature) for signature in [
    'private synchronized void markLinkAnswered(', 'private synchronized void markLinkFailed(',
    'private synchronized void markLinkBroughtNew(', 'private synchronized void resetLink(',
    'private synchronized void noteDialling(', 'private synchronized void noteConnected(',
    'private synchronized void cancelPacedReconnect(', 'private synchronized long pacedReconnectDelay(',
    'private synchronized void schedulePacedReconnect(', 'private synchronized void runPacedReconnect(',
    'synchronized void pacingChanged(',
])
# The pacing rule itself is covered by MeterSyncPacingTests; here the pacer is a recording fake.
assert 'class MeterLinkPacer' in pacing and 'QUICK_CONNECT_MS = 60_000L' in pacing

java = r'''
package tk.glucodata;
import java.util.*;
import java.util.concurrent.*;

public class GlucoseMeterGatt {
    static final boolean doLog=false;
    enum MeterLinkOutcome { UNANSWERED, NEW_READINGS, NOTHING_NEW, FAILED }
    static class MeterSyncPacing {
        static final long QUICK_CONNECT_MS=60_000L;
        static int minutes=5;
        static int minutes(){return minutes;}
    }
    static class MeterLinkPacer {
        MeterLinkOutcome outcome; long connectedAt,endedAt; boolean quick; int minutes; long nextWait;
        long linkEnded(MeterLinkOutcome o,long c,boolean q,long e,int m){outcome=o;connectedAt=c;quick=q;endedAt=e;minutes=m;return nextWait;}
    }
    static class SystemClock { static long elapsedRealtime(){return Applic.scheduler.now;} }
    static class Applic { static Scheduler scheduler=new Scheduler(); }
    static class Scheduler {
        long now=1_000_000L; List<Job> jobs=new ArrayList<>();
        ScheduledFuture<?> schedule(Runnable r,long delay,TimeUnit unit){Job j=new Job(r,now+unit.toMillis(delay));jobs.add(j);return j;}
        void advance(long delta){long target=now+delta;while(true){Job j=jobs.stream().filter(x->!x.done&&x.at<=target).min(Comparator.comparingLong(x->x.at)).orElse(null);if(j==null)break;now=j.at;j.fire();}now=target;}
        Job last(){return jobs.get(jobs.size()-1);}
    }
    static class Job implements ScheduledFuture<Object> {
        Runnable task; long at; boolean done,cancelled;
        Job(Runnable r,long a){task=r;at=a;}
        void fire(){done=true;if(!cancelled)task.run();}
        public boolean cancel(boolean interrupt){cancelled=true;done=true;return true;}
        public boolean isCancelled(){return cancelled;}public boolean isDone(){return done;}
        public Object get(){return null;}public Object get(long t,TimeUnit u){return null;}
        public long getDelay(TimeUnit u){return 0;}public int compareTo(Delayed d){return 0;}
    }
    boolean stop; Object mBluetoothGatt; int opened;
    void openGatt(){opened++;mBluetoothGatt=new Object();}
    private boolean linkAnswered=false;
    private boolean linkFailed=false;
    private boolean linkBroughtNew=false;
    private long dialStartedMs=0L;
    private long linkConnectedMs=0L;
    private boolean linkConnectedQuickly=false;
    private final MeterLinkPacer pacer=new MeterLinkPacer();
    private ScheduledFuture<?> pacedReconnect;
    private long pacedTicket=0L;
''' + methods + r'''
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static GlucoseMeterGatt fresh(){Applic.scheduler=new Scheduler();MeterSyncPacing.minutes=5;return new GlucoseMeterGatt();}
    public static void main(String[] args){
        // Each link reaches the pacer as one outcome, and the next link starts clean.
        GlucoseMeterGatt m=fresh();m.markLinkAnswered();m.pacedReconnectDelay();
        check(m.pacer.outcome==MeterLinkOutcome.NOTHING_NEW,"clean empty sync not NOTHING_NEW");
        m.pacedReconnectDelay();check(m.pacer.outcome==MeterLinkOutcome.UNANSWERED,"old sync leaked into a later link");
        m.markLinkFailed();m.pacedReconnectDelay();check(m.pacer.outcome==MeterLinkOutcome.FAILED,"failed sync not FAILED");
        m.markLinkFailed();m.markLinkBroughtNew();m.pacedReconnectDelay();
        check(m.pacer.outcome==MeterLinkOutcome.NEW_READINGS,"stored readings hidden by a later error");
        m.markLinkAnswered();m.noteConnected();m.pacedReconnectDelay();
        check(m.pacer.outcome==MeterLinkOutcome.UNANSWERED,"a new link kept the previous link's answer");
        check(m.pacer.minutes==5,"interval not passed through");

        // Connected within a minute of dialling: the meter was awake. Later, or never dialled: asleep.
        m=fresh();m.noteConnected();check(!m.linkConnectedQuickly,"undialled link counted as quick");
        m.noteDialling();Applic.scheduler.advance(60_000);m.noteConnected();check(m.linkConnectedQuickly,"60 s connect not quick");
        m.noteDialling();Applic.scheduler.advance(60_001);m.noteConnected();check(!m.linkConnectedQuickly,"slow connect counted as quick");
        long at=m.linkConnectedMs;Applic.scheduler.advance(9_000);m.markLinkAnswered();m.pacedReconnectDelay();
        check(m.pacer.connectedAt==at && m.pacer.endedAt==at+9_000,"link times not passed through");

        // The pacer's wait is scheduled, then dials once.
        m=fresh();m.pacer.nextWait=300_000L;check(m.pacedReconnectDelay()==300_000L,"pacer wait dropped");
        m.schedulePacedReconnect(300_000L);Applic.scheduler.advance(299_999);check(m.opened==0,"paced reconnect early");
        Applic.scheduler.advance(1);check(m.opened==1,"paced reconnect missing");

        // Review #800: a task the executor already started must not act once it is cancelled.
        m=fresh();m.schedulePacedReconnect(300_000L);Job started=Applic.scheduler.last();
        m.cancelPacedReconnect();started.task.run();check(m.opened==0,"cancelled task dialled after starting");
        // A newer link (retry, Bluetooth back on) is never replaced by the paced task.
        m=fresh();m.schedulePacedReconnect(300_000L);m.mBluetoothGatt=new Object();
        Applic.scheduler.advance(300_000);check(m.opened==0,"paced task replaced a live link");
        // Rescheduling supersedes the earlier ticket.
        m=fresh();m.schedulePacedReconnect(300_000L);Job first=Applic.scheduler.last();m.schedulePacedReconnect(600_000L);
        first.task.run();check(m.opened==0,"superseded paced task dialled");
        Applic.scheduler.advance(600_000);check(m.opened==1,"rescheduled task missing");
        // A meter removed while it waits stays removed.
        m=fresh();m.schedulePacedReconnect(300_000L);m.stop=true;Applic.scheduler.advance(300_000);
        check(m.opened==0,"removed meter dialled");

        // Changing the interval dials a waiting meter now, and only once.
        m=fresh();m.schedulePacedReconnect(1_800_000L);m.pacingChanged();check(m.opened==1,"interval change left meter waiting");
        Applic.scheduler.advance(1_800_000);check(m.opened==1,"old paced task dialled after interval change");
        m=fresh();m.pacingChanged();check(m.opened==0,"interval change dialled a meter that was not waiting");
        System.out.println("PASS: link outcomes, connect timing, paced reconnect, stale and superseded tasks, live links, removal, interval change");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='meter-pacing-') as directory:
    directory = Path(directory)
    (directory / 'GlucoseMeterGatt.java').write_text(java)
    subprocess.run(['javac', '-d', str(directory), str(directory / 'GlucoseMeterGatt.java')], check=True)
    subprocess.run(['java', '-cp', str(directory), 'tk.glucodata.GlucoseMeterGatt'], check=True)
