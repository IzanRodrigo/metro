// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irThrow
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name

/**
 * Generates a SwitchingProvider class following Dagger's pattern.
 * This centralizes all provider logic into a single class with integer IDs,
 * dramatically reducing the number of generated classes.
 *
 * Example generated code:
 * ```kotlin
 * private class SwitchingProvider<T>(
 *   private val graph: ApplicationComponent,
 *   private val id: Int
 * ) : Provider<T> {
 *   override fun invoke(): T {
 *     return when (id) {
 *       0 -> graph.service1Factory.create() as T
 *       1 -> graph.shard1.service2Provider() as T
 *       else -> throw AssertionError("Invalid id $id")
 *     }
 *   }
 * }
 * ```
 */
internal class SwitchingProviderGenerator(
  private val context: IrMetroContext,
  private val parentClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val shardingPlan: ShardingPlan?
) : IrMetroContext by context {

  companion object {
    private const val CLASS_NAME = "SwitchingProvider"
    private const val GRAPH_PARAM_NAME = "graph"
    private const val ID_PARAM_NAME = "id"
  }

  private var switchingProviderClass: IrClass? = null
  private val bindingIdMap = mutableMapOf<IrTypeKey, Int>()
  private var nextId = 0

  /**
   * Generates the SwitchingProvider class as a nested class in the main component.
   * This should only be called once per component.
   */
  fun generateSwitchingProviderClass(): IrClass {
    if (switchingProviderClass != null) {
      return switchingProviderClass!!
    }

    val providerClass = pluginContext.irFactory.buildClass {
      name = CLASS_NAME.asName()
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.PRIVATE
      modality = Modality.FINAL
      origin = Origins.Default
    }

    // Make it a nested class of the parent component
    parentClass.addChild(providerClass)

    // Add type parameter <T>
    val typeParam = providerClass.addTypeParameter {
      name = Name.identifier("T")
      index = 0
    }

    // Set supertype to Provider<T>
    val providerType = symbols.metroProvider.defaultType
    val substitutedProviderType = providerType.substitute(mapOf(
      providerType.arguments[0].typeOrNull!! to typeParam.defaultType
    )) as IrSimpleType
    providerClass.superTypes = listOf(substitutedProviderType)

    // Ensure thisReceiver is created
    if (providerClass.thisReceiver == null) {
      providerClass.createThisReceiverParameter()
    }

    // Add fields
    val graphField = providerClass.addField {
      name = GRAPH_PARAM_NAME.asName()
      type = parentClass.defaultType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
      origin = Origins.Default
    }

    val idField = providerClass.addField {
      name = ID_PARAM_NAME.asName()
      type = irBuiltIns.intType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
      origin = Origins.Default
    }

    // Add constructor
    val constructor = providerClass.addConstructor {
      visibility = DescriptorVisibilities.PUBLIC
      isPrimary = true
      returnType = providerClass.defaultType
    }

    val graphParam = constructor.addValueParameter(GRAPH_PARAM_NAME, parentClass.defaultType)
    val idParam = constructor.addValueParameter(ID_PARAM_NAME, irBuiltIns.intType)

    // Constructor body
    constructor.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = context.createIrBuilder(constructor.symbol)
      val thisRef = requireNotNull(providerClass.thisReceiver) {
        "SwitchingProvider class missing this receiver"
      }

      // Call super constructor
      statements += builder.irDelegatingConstructorCall(
        providerType.symbol.owner.constructors.first()
      )

      // Set fields
      statements += builder.irSetField(
        receiver = builder.irGet(thisRef),
        field = graphField,
        value = builder.irGet(graphParam)
      )

      statements += builder.irSetField(
        receiver = builder.irGet(thisRef),
        field = idField,
        value = builder.irGet(idParam)
      )

      // Call instance initializer
      statements += IrInstanceInitializerCallImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        providerClass.symbol,
        providerClass.defaultType
      )
    }

    // Add invoke() method
    val invokeMethod = providerClass.addFunction {
      name = Symbols.Names.invoke
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.OPEN
      returnType = typeParam.defaultType
      origin = Origins.Default
    }

    invokeMethod.overriddenSymbols = listOf(
      symbols.metroProviderInvoke
    )

    // The invoke method body will be populated later with all the cases
    // For now, create an empty body that will be filled by addBindingCase
    invokeMethod.body = irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET)

    switchingProviderClass = providerClass
    return providerClass
  }

  /**
   * Assigns a unique ID to a binding and returns it.
   * IDs are assigned sequentially to ensure stability.
   */
  fun assignBindingId(typeKey: IrTypeKey): Int {
    return bindingIdMap.getOrPut(typeKey) {
      nextId++
    }
  }

  /**
   * Gets the ID for a binding, or null if not assigned yet.
   */
  fun getBindingId(typeKey: IrTypeKey): Int? {
    return bindingIdMap[typeKey]
  }

  /**
   * Creates an expression that instantiates a SwitchingProvider for the given binding.
   * Example: SwitchingProvider<Service>(this, 42)
   */
  fun createSwitchingProviderInstance(
    typeKey: IrTypeKey,
    graphInstance: IrExpression,
    builder: IrBuilderWithScope
  ): IrExpression {
    val providerClass = switchingProviderClass
      ?: error("SwitchingProvider class not generated yet")

    val bindingId = getBindingId(typeKey)
      ?: error("No ID assigned for binding $typeKey")

    val constructor = providerClass.primaryConstructor
      ?: error("SwitchingProvider missing primary constructor")

    return builder.irCall(constructor.symbol).apply {
      // Set type argument
      putTypeArgument(0, typeKey.type)
      // Set constructor arguments
      putValueArgument(0, graphInstance)
      putValueArgument(1, builder.irInt(bindingId))
    }
  }

  /**
   * Populates the invoke() method body with all binding cases.
   * This should be called after all bindings have been assigned IDs.
   *
   * @param bindingExpressions Map of binding IDs to expressions that create the instances
   */
  fun populateInvokeMethod(bindingExpressions: Map<Int, (IrBlockBodyBuilder) -> IrExpression>) {
    val providerClass = switchingProviderClass
      ?: error("SwitchingProvider class not generated yet")

    val invokeMethod = providerClass.declarations
      .filterIsInstance<IrSimpleFunction>()
      .find { it.name == Symbols.Names.invoke }
      ?: error("SwitchingProvider missing invoke method")

    val thisRef = requireNotNull(providerClass.thisReceiver) {
      "SwitchingProvider class missing this receiver"
    }

    val idField = providerClass.declarations
      .filterIsInstance<IrField>()
      .find { it.name.asString() == ID_PARAM_NAME }
      ?: error("SwitchingProvider missing id field")

    // Build the method body with a when expression
    invokeMethod.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = context.createIrBuilder(invokeMethod.symbol)

      // Build when branches for each binding
      val branches = bindingExpressions.map { (id, exprBuilder) ->
        builder.irBranch(
          condition = builder.irEquals(
            builder.irGetField(builder.irGet(thisRef), idField),
            builder.irInt(id)
          ),
          result = exprBuilder(builder as IrBlockBodyBuilder)
        )
      }.toMutableList()

      // Add else branch that throws AssertionError
      branches += builder.irElseBranch(
        builder.irThrow(
          builder.irCall(symbols.assertionError.constructors.first()).apply {
            putValueArgument(0, builder.irString("Invalid SwitchingProvider id: \${id}"))
          }
        )
      )

      // Create the when expression and return its result
      statements += builder.irReturn(
        builder.irWhen(
          invokeMethod.returnType,
          branches
        )
      )
    }
  }

  /**
   * Extension function to create string literals
   */
  private fun IrBuilderWithScope.irString(value: String): IrExpression {
    return irCall(irBuiltIns.stringClass.constructors.first()).apply {
      putValueArgument(0, irConst(value))
    }
  }

  /**
   * Extension function to create const expressions
   */
  private fun IrBuilderWithScope.irConst(value: Any?): IrExpression {
    return when (value) {
      is String -> IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.stringType, value)
      is Int -> IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.intType, value)
      else -> error("Unsupported const type: ${value?.javaClass}")
    }
  }
}

/**
 * Data class to hold binding case information for the SwitchingProvider
 */
data class SwitchingProviderCase(
  val id: Int,
  val typeKey: IrTypeKey,
  val expressionBuilder: (IrBlockBodyBuilder) -> IrExpression
)