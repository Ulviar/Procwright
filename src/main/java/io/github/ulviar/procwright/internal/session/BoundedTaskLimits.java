/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

/** Process-wide admission partitions for independent bounded-operation categories. */
final class BoundedTaskLimits {

    static final BoundedTaskLimiter READINESS_PROBES = new BoundedTaskLimiter(16);
    static final BoundedTaskLimiter WORKER_HOOKS = new BoundedTaskLimiter(16);
    static final BoundedTaskLimiter PROTOCOL_CALLBACKS = new BoundedTaskLimiter(64);
    static final BoundedTaskLimiter TEXT_ENCODINGS = new BoundedTaskLimiter(32);
    static final BoundedTaskLimiter BLOCKING_WRITES = new BoundedTaskLimiter(32);
    static final BoundedTaskLimiter WORKER_STARTUPS = new BoundedTaskLimiter(16);
    static final BoundedTaskLimiter REGEX_MATCHES = new BoundedTaskLimiter(8);

    private BoundedTaskLimits() {}
}
