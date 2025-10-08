package dev.zacsweers.metro.compiler.ir

/**
 * Shared internal constants for IR code generation heuristics.
 *
 * Values here should remain stable unless there is a measured reason to change them.
 */
internal object MetroConstants {
  /**
   * Maximum number of initialization statements (or shard init method calls) per generated
   * method block. Mirrors Dagger's long-standing 25-statement heuristic to balance dex size
   * and avoid hitting JVM/Dalvik/ART method size limits while not exploding method counts.
   */
  internal const val MAX_INIT_STATEMENTS_PER_METHOD: Int = 25
}
