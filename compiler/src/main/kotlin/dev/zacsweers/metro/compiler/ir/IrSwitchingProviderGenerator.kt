// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("DEPRECATION")

package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass

internal class IrSwitchingProviderGenerator(
  private val context: IrMetroContext,
) : IrMetroContext by context {
  fun generateSwitchingProviderClass(): IrClass {
    val switchingProviderClass = pluginContext.irFactory.buildClass {
      name = Symbols.Names.SwitchingProvider
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.INTERNAL
      modality = Modality.FINAL
      origin = Origins.Default
    }

    // TODO: Generate the rest of the code.

    return switchingProviderClass
  }
}
