package com.aurex.loader;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.util.List;
import java.util.Locale;

/**
 * Does the actual JVM attach. Loaded by {@link Main} AFTER tools.jar is
 * spliced onto the system classloader, so com.sun.tools.attach.* references
 * resolve cleanly.
 *
 * Never call directly from Main — always via reflection so the JVM doesn't
 * try to link this class before tools.jar is on the classpath.
 */
public final class Attacher {
    /** Lunar's bootstrap class, confirmed via `jps -l`. */
    private static final String LUNAR_MAIN = "com.moonsworth.lunar.genesis.Genesis";

    private Attacher() {}

    /**
     * Invoked from Main via reflection.
     *
     * @param agentJarPath  absolute path to aurex-agent.jar
     * @param explicitPid   user-supplied PID as a string, or null to auto-discover
     */
    public static void run(String agentJarPath, String explicitPid) throws Exception {
        String targetPid;
        String targetDescription;

        if (explicitPid != null) {
            // User told us the PID directly. Skip the discovery dance — it's
            // unreliable when Lunar uses a bundled JVM that doesn't register
            // with our JDK's hsperfdata.
            targetPid = explicitPid;
            targetDescription = "pid=" + explicitPid + " (user-supplied)";
        } else {
            VirtualMachineDescriptor lunar = findLunar();
            if (lunar == null) {
                System.exit(2);
                return;
            }
            targetPid = lunar.id();
            targetDescription = "pid=" + lunar.id() + " (" + lunar.displayName() + ")";
        }

        System.out.println("Target: " + targetDescription);

        // VirtualMachine.attach(pidString) works on a raw PID — no need for
        // the descriptor. Uses platform IPC (named pipe on Windows) to talk
        // to the target JVM.
        VirtualMachine vm;
        try {
            vm = VirtualMachine.attach(targetPid);
        } catch (Exception e) {
            System.err.println("ERROR: attach(" + targetPid + ") failed: " + e);
            System.err.println("Is the PID correct? Is Lunar still running?");
            System.err.println("Run `jps -l` to verify.");
            throw e;
        }

        try {
            System.out.println("Attached. Loading agent jar...");
            // loadAgent blocks until agentmain() returns on the target JVM.
            vm.loadAgent(agentJarPath);
            System.out.println("Agent loaded successfully.");
            System.out.println("Check log at %APPDATA%\\Aurex\\agent.log");
        } finally {
            vm.detach();
        }
    }

    /**
     * Find Lunar in VirtualMachine.list(). Returns null if not found.
     * Uses a loose match (any of "moonsworth", "lunar", "genesis") because
     * Lunar has changed display names across versions, and the match is
     * already scoped to JVMs on this machine.
     */
    private static VirtualMachineDescriptor findLunar() {
        List<VirtualMachineDescriptor> vms = VirtualMachine.list();
        for (VirtualMachineDescriptor vm : vms) {
            if (vm.displayName().startsWith(LUNAR_MAIN)) {
                return vm; // exact match, preferred
            }
        }
        // Fall back to loose match.
        for (VirtualMachineDescriptor vm : vms) {
            String dn = vm.displayName().toLowerCase(Locale.ROOT);
            if (dn.contains("moonsworth") || dn.contains("lunar") || dn.contains("genesis")) {
                System.out.println("Loose match: " + vm.displayName());
                return vm;
            }
        }
        System.err.println("ERROR: no running Lunar Client JVM found via VirtualMachine.list().");
        System.err.println("This is a known quirk when Lunar bundles its own JVM.");
        System.err.println("Workaround: find Lunar's PID with `jps -l`, then re-run with:");
        System.err.println("  java -jar aurex-loader.jar --pid <PID>");
        if (vms.isEmpty()) {
            System.err.println("(no JVMs visible at all — unusual)");
        } else {
            System.err.println("Visible JVMs were:");
            for (VirtualMachineDescriptor vm : vms) {
                System.err.println("  pid=" + vm.id() + " name=" + vm.displayName());
            }
        }
        return null;
    }
}
