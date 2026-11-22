package gesher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Real ProcessBuilder -> libcore reaper -> guest JNI resource queries. */
public final class ChildUsageProcessTest {
    private static native long[] snapshot();

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static long[] usage() {
        long[] result = snapshot();
        check(result != null && result.length == 5 && result[4] > 0, "resource queries succeed");
        check(result[0] >= 0 && result[1] >= 0, "CPU totals remain nonnegative");
        check(Math.abs(result[2] - result[0] * result[4] / 1_000_000) <= 1
                && Math.abs(result[3] - result[1] * result[4] / 1_000_000) <= 1,
                "guest times and getrusage share libcore-reaped children");
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("JNI library and child executable required");
        System.load(args[0]);
        for (int round = 0; round < 2; ++round) {
            long[] before = usage();
            Process child = new ProcessBuilder(args[1], "--child").redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                check(line != null && line.startsWith("ready "), "actual CPU-burning child reaches handshake");
                long readyCpu = Long.parseLong(line.substring(6));
                check(readyCpu >= 50_000, "child measured at least 50 ms process CPU");
                long[] running = usage();
                check(running[0] == before[0] && running[1] == before[1], "live Java child is not reaped yet");
                child.getOutputStream().write('x');
                child.getOutputStream().close();
                check(child.waitFor() == 37, "Java receives the real child's exit status");
                long[] after = usage();
                check(after[0] + after[1] - before[0] - before[1] >= readyCpu,
                        "libcore reaper contributes actual child CPU to guest queries");
                check(child.waitFor() == 37, "repeat Java wait returns its cached status");
                long[] repeated = usage();
                check(repeated[0] == after[0] && repeated[1] == after[1], "repeat wait/query does not double count");
            } finally {
                child.destroy();
            }
        }
        System.out.println("ChildUsageProcessTest all passed");
    }
}
