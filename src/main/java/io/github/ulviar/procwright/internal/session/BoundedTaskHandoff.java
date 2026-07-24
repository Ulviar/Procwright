/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

/** Records whether an admitted task may already produce an external side effect. */
abstract class BoundedTaskHandoff {

    private static final BoundedTaskHandoff UNTRACKED = new BoundedTaskHandoff() {
        @Override
        void rejectBeforeAdmission() {}

        @Override
        void rejectIfWaiting() {}

        @Override
        void admit() {}
    };

    static BoundedTaskHandoff untracked() {
        return UNTRACKED;
    }

    abstract void rejectBeforeAdmission();

    abstract void rejectIfWaiting();

    abstract void admit();
}
