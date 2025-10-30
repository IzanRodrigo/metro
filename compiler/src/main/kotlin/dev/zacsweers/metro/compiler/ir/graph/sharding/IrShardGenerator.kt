// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.Name

internal object IrShardGenerator {

  context(context: IrMetroContext)
  fun generateShards(
    graphClass: IrClass,
    shardGroups: List<List<PropertyBinding>>,
  ): List<ShardInfo> {
    return shardGroups.mapIndexed { index, bindings -> generateShard(graphClass, index, bindings) }
  }

  context(context: IrMetroContext)
  private fun generateShard(
    graphClass: IrClass,
    shardIndex: Int,
    bindings: List<PropertyBinding>,
  ): ShardInfo {
    val shardName = "Shard${shardIndex + 1}"

    val shardClass =
      context.irFactory
        .buildClass {
          name = Name.identifier(shardName)
          visibility = DescriptorVisibilities.INTERNAL
          modality = Modality.FINAL
        }
        .apply {
          superTypes = listOf(context.irBuiltIns.anyType)
          // Must initialize thisReceiver before other operations
          createThisReceiverParameter()
          parent = graphClass
          graphClass.addChild(this)
        }

    shardClass
      .addConstructor {
        isPrimary = true
        visibility = DescriptorVisibilities.INTERNAL
        returnType = shardClass.defaultType
      }
      .apply { body = generateDefaultConstructorBody() }

    val shardInstanceProperty =
      graphClass
        .addProperty {
          this.name = Name.identifier(shardName.replaceFirstChar { it.lowercase() })
          this.visibility = DescriptorVisibilities.PRIVATE
        }
        .apply {
          addBackingField {
            type = shardClass.defaultType
            visibility = DescriptorVisibilities.PRIVATE
          }

          addGetter {
              returnType = shardClass.defaultType
              visibility = DescriptorVisibilities.PRIVATE
            }
            .apply {
              val getterReceiver = graphClass.thisReceiver!!.copyTo(this)
              setDispatchReceiver(getterReceiver)

              body =
                context.createIrBuilder(symbol).irBlockBody {
                  +irReturn(irGetField(irGet(getterReceiver), backingField!!))
                }
            }
        }

    val initializeFunction =
      shardClass
        .addFunction {
          name = Name.identifier("initialize")
          returnType = context.irBuiltIns.unitType
          visibility = DescriptorVisibilities.INTERNAL
        }
        .apply {
          val shardReceiver = shardClass.thisReceiver!!.copyTo(this)
          setDispatchReceiver(shardReceiver)

          val componentParam = addValueParameter {
            name = Name.identifier("component")
            type = graphClass.defaultType
          }

          body =
            context.createIrBuilder(symbol).irBlockBody {
              for (binding in bindings) {
                val property = binding.property
                val backingField = property.backingField

                if (backingField != null) {
                  val initValue =
                    binding.initializer.invoke(this@irBlockBody, componentParam, binding.typeKey)
                  +irSetField(irGet(componentParam), backingField, initValue)
                }
              }
            }
        }

    return ShardInfo(
      index = shardIndex,
      shardClass = shardClass,
      instanceProperty = shardInstanceProperty,
      initializeFunction = initializeFunction,
      bindings = bindings,
    )
  }
}
