package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.ssh.SshKey

/**
 * Host-side session-teardown port used by [DefaultCockpitLifecycle]. Narrow
 * enough that host-JVM tests can supply an in-memory fake instead of driving
 * a real SSH round-trip through [HostSessionTerminator].
 */
interface HostTermination {
    /** Kill only the tmux sessions backing [snapshots]. Returns whether it succeeded. */
    fun terminate(payload: PairingPayload, key: SshKey?, snapshots: List<SessionSnapshot>): Boolean

    /** Kill the entire dedicated tmux server (`-L gxr`). Returns whether it succeeded. */
    fun terminateAll(payload: PairingPayload, key: SshKey?): Boolean
}

/** Production [HostTermination] wrapping [HostSessionTerminator]. */
class HostSessionTermination(
    private val terminator: HostSessionTerminator,
) : HostTermination {
    override fun terminate(payload: PairingPayload, key: SshKey?, snapshots: List<SessionSnapshot>): Boolean =
        terminator.terminate(payload, key, snapshots)

    override fun terminateAll(payload: PairingPayload, key: SshKey?): Boolean =
        terminator.terminateAll(payload, key)
}
