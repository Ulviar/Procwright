/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines how command output is captured for diagnostics and results.
 *
 * <p>Three families are supported for one-shot runs:
 *
 * <ul>
 *   <li>{@link #bounded(int)} retains output in memory up to a byte limit per stream;
 *   <li>{@link #discard()} drops both streams at the operating-system level without pump threads;
 *   <li>{@link #toPath(Path, Path)} and {@link #toPath(Path)} redirect streams to files at the operating-system
 *       level.
 * </ul>
 *
 * <p>For discarded and redirected streams the {@link CommandResult} text and byte accessors return empty values and
 * the truncation flags stay {@code false}; timeout supervision, process-tree cleanup, exit code, {@code timedOut},
 * and {@code elapsed} reporting are unchanged.
 */
public sealed interface CapturePolicy permits CapturePolicy.Bounded, CapturePolicy.Discard, CapturePolicy.ToPath {

    /**
     * Captures output up to a fixed byte limit.
     *
     * @param byteLimit maximum number of bytes retained per stream
     * @return a bounded capture policy
     */
    static Bounded bounded(int byteLimit) {
        return new Bounded(byteLimit);
    }

    /**
     * Discards both output streams at the operating-system level.
     *
     * <p>No output pump threads run. {@link CommandResult#stdout()} and {@link CommandResult#stderr()} are empty and
     * the truncation flags are {@code false}.
     *
     * @return a discarding capture policy
     */
    static Discard discard() {
        return new Discard();
    }

    /**
     * Redirects stdout and stderr to two distinct files at the operating-system level.
     *
     * <p>This form requires {@link OutputMode#SEPARATE}. Existing file content is overwritten. No output pump
     * threads run, so memory usage stays constant regardless of output volume. {@link CommandResult#stdout()} and
     * {@link CommandResult#stderr()} are empty and the truncation flags are {@code false}.
     * Immediately before launch, Procwright rejects targets that resolve to the same file, including aliases through
     * symlinked directories. It also rejects any pair of target names that differ only by case, canonical Unicode
     * representation, or trailing dots and spaces, even when both files already exist and the local filesystem treats
     * them as distinct. Do not replace or relink either path concurrently with process launch: the JDK redirect API does
     * not provide an atomic two-target identity check and open operation.
     *
     * @param stdout target file for standard output
     * @param stderr target file for standard error, distinct from {@code stdout}
     * @return a file-redirecting capture policy
     */
    static ToPath toPath(Path stdout, Path stderr) {
        return new ToPath(stdout, Optional.of(Objects.requireNonNull(stderr, "stderr")));
    }

    /**
     * Redirects the merged output stream to one file at the operating-system level.
     *
     * <p>This form requires {@link OutputMode#MERGED}: stderr is merged into stdout and the combined stream is
     * written to {@code merged}. Existing file content is overwritten. No output pump threads run.
     * {@link CommandResult#stdout()} and {@link CommandResult#stderr()} are empty and the truncation flags are
     * {@code false}.
     *
     * @param merged target file for the merged stdout and stderr stream
     * @return a file-redirecting capture policy
     */
    static ToPath toPath(Path merged) {
        return new ToPath(merged, Optional.empty());
    }

    /**
     * Capture policy that retains at most {@code byteLimit} bytes.
     *
     * @param byteLimit maximum number of bytes retained per stream
     */
    record Bounded(int byteLimit) implements CapturePolicy {

        /**
         * Creates a bounded capture policy.
         *
         * @param byteLimit maximum number of bytes retained per stream
         */
        public Bounded {
            if (byteLimit <= 0) {
                throw new IllegalArgumentException("byteLimit must be positive");
            }
        }
    }

    /**
     * Capture policy that discards both output streams at the operating-system level.
     */
    record Discard() implements CapturePolicy {}

    /**
     * Capture policy that redirects output streams to files at the operating-system level.
     *
     * <p>When {@code stderr} is present the policy redirects the streams separately and requires
     * {@link OutputMode#SEPARATE}; when it is empty, {@code stdout} receives the merged stream and the policy
     * requires {@link OutputMode#MERGED}.
     *
     * @param stdout target file for standard output, or for the merged stream when {@code stderr} is empty
     * @param stderr target file for standard error, or empty for the merged single-file form
     */
    record ToPath(Path stdout, Optional<Path> stderr) implements CapturePolicy {

        /**
         * Creates a file-redirecting capture policy.
         *
         * @param stdout target file for standard output, or for the merged stream when {@code stderr} is empty
         * @param stderr target file for standard error, or empty for the merged single-file form
         */
        public ToPath {
            requirePath(stdout, "stdout");
            Objects.requireNonNull(stderr, "stderr");
            stderr.ifPresent(path -> {
                requirePath(path, "stderr");
                if (normalized(path).equals(normalized(stdout))) {
                    throw new IllegalArgumentException("stdout and stderr capture paths must be distinct");
                }
            });
        }

        /**
         * Returns whether this policy is the merged single-file form.
         *
         * @return {@code true} when stderr is merged into the {@link #stdout()} target file
         */
        public boolean merged() {
            return stderr.isEmpty();
        }

        private static void requirePath(Path path, String name) {
            Objects.requireNonNull(path, name);
            if (path.toString().isBlank()) {
                throw new IllegalArgumentException(name + " capture path must not be blank");
            }
        }

        private static Path normalized(Path path) {
            return path.toAbsolutePath().normalize();
        }
    }
}
