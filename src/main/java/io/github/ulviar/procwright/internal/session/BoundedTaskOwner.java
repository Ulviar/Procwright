/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.Threading;
import java.util.Objects;

/** Selects the explicit owner that starts one admitted bounded task. */
interface BoundedTaskOwner {

    void start(String threadPrefix, String taskName, Runnable task, BoundedTaskRunner.TaskRejection rejection);

    static BoundedTaskOwner fresh() {
        return FreshOwner.INSTANCE;
    }

    static BoundedTaskOwner fresh(BoundedTaskRunner.TaskThreadFactory threadFactory) {
        return new FactoryOwner(Objects.requireNonNull(threadFactory, "threadFactory"));
    }

    static BoundedTaskOwner delegated(BoundedTaskRunner.TaskStarter taskStarter) {
        return new DelegatedOwner(Objects.requireNonNull(taskStarter, "taskStarter"));
    }

    enum FreshOwner implements BoundedTaskOwner {
        INSTANCE;

        @Override
        public void start(
                String threadPrefix, String taskName, Runnable task, BoundedTaskRunner.TaskRejection rejection) {
            Threading.unstartedPlatformNonInheriting(taskName, task).start();
        }
    }

    record FactoryOwner(BoundedTaskRunner.TaskThreadFactory threadFactory) implements BoundedTaskOwner {

        @Override
        public void start(
                String threadPrefix, String taskName, Runnable task, BoundedTaskRunner.TaskRejection rejection) {
            Thread thread =
                    Objects.requireNonNull(threadFactory.unstarted(threadPrefix, task), "threadFactory returned null");
            thread.setDaemon(true);
            thread.start();
        }
    }

    record DelegatedOwner(BoundedTaskRunner.TaskStarter taskStarter) implements BoundedTaskOwner {

        @Override
        public void start(
                String threadPrefix, String taskName, Runnable task, BoundedTaskRunner.TaskRejection rejection) {
            taskStarter.start(threadPrefix, task, rejection);
        }
    }
}
