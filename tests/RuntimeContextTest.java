import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.RuntimeContext;

import android.content.Context;
import android.os.Process;

/** Each scenario runs in its own process, as application identity is process-wide. */
public final class RuntimeContextTest {
    private RuntimeContextTest() {
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return;
        }
        throw new AssertionError("Expected identity validation failure");
    }

    public static void main(String[] args) {
        switch (args[0]) {
            case "system-missing":
                Process.uid = 1000;
                check(!RuntimeContext.isShellRuntime(), "System UID must never be considered shell");
                expectFailure(RuntimeContext::get);
                check(FakeContext.requests == 0, "No shell fallback for system UID");
                break;
            case "app-missing":
                Process.uid = 10123;
                expectFailure(RuntimeContext::get);
                check(FakeContext.requests == 0, "No shell fallback for application UID");
                break;
            case "shell":
                Process.uid = 2000;
                check(RuntimeContext.isShellRuntime(), "Legacy adb runtime");
                check(RuntimeContext.get() instanceof FakeContext, "Legacy shell keeps its explicit context");
                check(RuntimeContext.getUserId() == 0, "Settings API uses user 0, not UID 2000");
                break;
            case "system-app":
                Process.uid = 1000;
                Context app = new Context(1000, "com.genymobile.scrcpy");
                Context service = new Context(1000, "com.genymobile.scrcpy");
                service.application = app;
                RuntimeContext.initialize(service);
                check(RuntimeContext.get() == app, "Use application context instead of retaining a Service");
                check(RuntimeContext.get().getPackageName().equals("com.genymobile.scrcpy"), "Real application package");
                check(RuntimeContext.get().getApplicationInfo().uid == Process.uid, "Real process UID");
                check(!RuntimeContext.isShellRuntime(), "Application never enters shell workaround");
                check(FakeContext.requests == 0, "Application never constructs fake context");
                break;
            case "invalid-context":
                Process.uid = 1000;
                expectFailure(() -> RuntimeContext.initialize(null));
                expectFailure(() -> RuntimeContext.initialize(new Context(2000, "com.android.shell")));
                expectFailure(() -> RuntimeContext.initialize(new FakeContext()));
                expectFailure(RuntimeContext::get);
                break;
            case "multiuser":
                Process.uid = 101000;
                RuntimeContext.initialize(new Context(101000, "com.genymobile.scrcpy"));
                check(RuntimeContext.getUserId() == 1, "Use Android user ID instead of Linux UID");
                check(FakeContext.requests == 0, "Secondary user still uses application identity");
                break;
            default:
                throw new AssertionError("Unknown scenario");
        }
        System.out.println("PASS identity: " + args[0]);
    }
}
