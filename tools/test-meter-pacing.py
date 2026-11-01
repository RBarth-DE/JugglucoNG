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
    'private synchronized void markLinkSynced(', 'private synchronized void markLinkBroughtNew(',
    'private synchronized void cancelPacedReconnect(', 'private synchronized long pacedReconnectDelay(',
    'private synchronized void schedulePacedReconnect(', 'private synchronized void runPacedReconnect(',
    'synchronized void pacingChanged(',
])
# The Kotlin policy is one expression; keep the test honest about its rule.
assert 'if (synced && !broughtNew && minutes > 0) minutes * 60_000L else 0L' in pacing

java = r'''
package tk.glucodata;
import java.util.*;
import java.util.concurrent.*;

public class GlucoseMeterGatt {
    static final boolean doLog=false;
    static class MeterSyncPacing {
        static int minutes=5;
        static int minutes(){return minutes;}
        static long reconnectDelayMillis(boolean synced,boolean broughtNew,int minutes){
            return synced&&!broughtNew&&minutes>0?minutes*60_000L:0L;
        }
    }
    static class Applic { static Scheduler scheduler=new Scheduler(); }
    static class Scheduler {
        long now; List<Job> jobs=new ArrayList<>();
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
    private boolean linkSynced=false;
    private boolean linkBroughtNew=false;
    private ScheduledFuture<?> pacedReconnect;
    private long pacedTicket=0L;
''' + methods + r'''
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static GlucoseMeterGatt fresh(){Applic.scheduler=new Scheduler();MeterSyncPacing.minutes=5;return new GlucoseMeterGatt();}
    public static void main(String[] args){
        // A sync with nothing new waits the interval, then dials once.
        GlucoseMeterGatt m=fresh();m.markLinkSynced();
        check(m.pacedReconnectDelay()==300_000L,"empty sync not paced");
        m.schedulePacedReconnect(300_000L);Applic.scheduler.advance(299_999);check(m.opened==0,"paced reconnect early");
        Applic.scheduler.advance(1);check(m.opened==1,"paced reconnect missing");

        // New readings, no completed sync, or the interval set to 0: reconnect at once, as before.
        m=fresh();m.markLinkSynced();m.markLinkBroughtNew();check(m.pacedReconnectDelay()==0L,"new readings were paced");
        m=fresh();check(m.pacedReconnectDelay()==0L,"link without a sync was paced");
        m=fresh();MeterSyncPacing.minutes=0;m.markLinkSynced();check(m.pacedReconnectDelay()==0L,"interval 0 still paced");
        // The verdict belongs to one link: the next one starts clean.
        m=fresh();m.markLinkSynced();m.pacedReconnectDelay();check(m.pacedReconnectDelay()==0L,"old sync paced a later link");

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
        System.out.println("PASS: meter reconnect pacing, stale and superseded tasks, live links, removal, interval change");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='meter-pacing-') as directory:
    directory = Path(directory)
    (directory / 'GlucoseMeterGatt.java').write_text(java)
    subprocess.run(['javac', '-d', str(directory), str(directory / 'GlucoseMeterGatt.java')], check=True)
    subprocess.run(['java', '-cp', str(directory), 'tk.glucodata.GlucoseMeterGatt'], check=True)
