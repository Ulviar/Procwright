/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Reusable invocation presets for common workflow shapes.
 *
 * <p>{@link io.github.ulviar.procwright.preset.ScenarioPresets} packages recurring configuration bundles (for
 * example bounded binary output capture) as invocation-builder callbacks. Presets only configure builders — they do
 * not create runners or bypass the resolver, so everything a preset does can also be written out explicitly at the
 * call site.
 */
package io.github.ulviar.procwright.preset;
