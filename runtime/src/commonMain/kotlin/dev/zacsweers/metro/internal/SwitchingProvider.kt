// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider

/**
 * A shared Provider implementation that multiplexes instance creation by an integer id.
 *
 * This enables "fast-init" by reducing the number of generated provider classes. The compiler
 * generates a small selector function (typically one per shard) and binds it here along with an
 * id to select the target binding at invocation time.
 */
public class SwitchingProvider<T>(
  private val id: Int,
  private val selector: (Int) -> Any?,
) : Provider<T> {
  /**
   * Convenience constructor for simple delegation to a single provider. This can be used by
   * compilers that don't need multiplexing by id but still want to minimize generated shapes.
   */
  public constructor(provider: Provider<T>) : this(0, { _ -> provider.invoke() as Any? })
  @Suppress("UNCHECKED_CAST")
  override fun invoke(): T = selector(id) as T
}
