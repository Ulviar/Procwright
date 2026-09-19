/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

/**
 * Defines how stdout and stderr are routed before a one-shot run applies its {@link CapturePolicy}.
 */
public enum OutputMode {
    /**
     * Keeps stdout and stderr independent. Bounded capture applies its byte limit separately to each stream.
     */
    SEPARATE,

    /**
     * Redirects stderr into stdout before capture. Standard-error text and bytes are empty and its truncation flag
     * is false; stdout carries the combined captured output. Ordering follows the child process and operating system,
     * not an ordering reconstructed by Procwright.
     */
    MERGED
}
