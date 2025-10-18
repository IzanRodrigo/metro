// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.stubExpression
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.copyTo

/**
 * Generates the fast-init switching provider class for a graph. This mirrors the safe IR creation
 * patterns we already use for shard classes so it compiles on the older K2 compat shards.
 */
internal class SwitchingProviderGenerator(
  private val context: IrMetroContext,
  private val graphClass: IrClass,
  private val fieldNameAllocator: NameAllocator,
  private val expressionGeneratorFactory: IrGraphExpressionGenerator.Factory,
) : IrMetroContext by context {

  private data class SwitchingProviderBindingInfo(
    val id: Int,
    val function: IrSimpleFunction,
  )

  private val bindingInfos = mutableMapOf<IrTypeKey, SwitchingProviderBindingInfo>()
  private var nextBindingId = 0

  private val switchingProviderClass by lazy { createSwitchingProviderClass() }

  fun createProviderExpression(
    scope: IrBuilderWithScope,
    componentReceiver: IrValueParameter,
    binding: IrBinding,
  ): IrExpression {
    if (!binding.isSwitchingCandidate()) {
      val generator = expressionGeneratorFactory.create(componentReceiver)
      return generator.generateBindingCode(
        binding,
        accessType = IrGraphExpressionGenerator.AccessType.PROVIDER,
      )
    }
    val info = bindingInfos.getOrPut(binding.typeKey) { createBindingInfo(binding) }
    switchingProviderClass.ensureCase(info)
    val rawProvider = switchingProviderClass.newInstance(scope, componentReceiver, info.id)
    return scope.castToProvider(rawProvider, binding.typeKey.type)
  }

  private fun createBindingInfo(binding: IrBinding): SwitchingProviderBindingInfo {
    val id = nextBindingId++
    val functionName =
      fieldNameAllocator.newName(
        binding.nameHint.decapitalizeUS().suffixIfNot("SwitchingProviderCase"),
      )
    val function =
      graphClass
        .addFunction(functionName, binding.typeKey.type)
        .apply {
          visibility = DescriptorVisibilities.PRIVATE
          setDispatchReceiver(graphClass.thisReceiverOrFail.copyTo(this))
          buildBlockBody {
            val dispatchReceiver = dispatchReceiverParameter!!
            val expression =
              expressionGeneratorFactory
                .create(dispatchReceiver)
                .generateBindingCode(
                  binding,
                  accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
                  fieldInitKey = binding.typeKey,
                )
            +irReturn(expression)
          }
        }
    return SwitchingProviderBindingInfo(id, function)
  }

  private inner class SwitchingProviderClass(
    val irClass: IrClass,
    val componentField: IrField,
    val idField: IrField,
    val constructor: IrConstructor,
    val invokeFunction: IrSimpleFunction,
  ) {

    private val cases = mutableMapOf<Int, IrSimpleFunction>()

    fun ensureCase(info: SwitchingProviderBindingInfo) {
      if (cases.putIfAbsent(info.id, info.function) == null) {
        rebuildInvokeBody()
      }
    }

    fun newInstance(
      scope: IrBuilderWithScope,
      componentReceiver: IrValueParameter,
      id: Int,
    ): IrExpression {
      return scope.irCallConstructor(constructor.symbol, emptyList()).apply {
        arguments[0] = scope.irGet(componentReceiver)
        arguments[1] = scope.irInt(id)
      }
    }

    private fun rebuildInvokeBody() {
      val builder = context.createIrBuilder(invokeFunction.symbol)
      val dispatchReceiver = invokeFunction.dispatchReceiverParameter!!
      invokeFunction.body = builder.irBlockBody {
        val idExpression = builder.irGetField(builder.irGet(dispatchReceiver), idField)
        val whenExpression =
          IrWhenImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            builder.context.irBuiltIns.anyNType,
            null,
          )

        for ((caseId, function) in cases.toSortedMap()) {
          val condition =
            builder.irCall(builder.context.irBuiltIns.eqeqSymbol).apply {
              arguments[0] = idExpression
              arguments[1] = builder.irInt(caseId)
            }
          val component = builder.irGetField(builder.irGet(dispatchReceiver), componentField)
          val call = builder.irCall(function.symbol).apply { setDispatchReceiver(component) }
          val result = builder.castToAny(call)
          whenExpression.branches +=
            IrBranchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, condition, result)
        }

        val elseExpression = builder.stubExpression("Invalid switching provider id")
        whenExpression.branches +=
          IrElseBranchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builder.irTrue(), elseExpression)

        +builder.irReturn(whenExpression)
      }
    }
  }

  private fun createSwitchingProviderClass(): SwitchingProviderClass {
    val className = fieldNameAllocator.newName("SwitchingProvider").asName()
    val irClass =
      pluginContext.irFactory
        .buildClass {
          name = className
          visibility = DescriptorVisibilities.PRIVATE
          kind = ClassKind.CLASS
        }
        .apply {
          superTypes = listOf(metroSymbols.metroProvider.typeWith(irBuiltIns.anyNType))
          createThisReceiverParameter()
          graphClass.addChild(this)
        }

    val componentField =
      irClass
        .addField(fieldNameAllocator.newName("component"), graphClass.defaultType)
        .apply {
          visibility = DescriptorVisibilities.PRIVATE
          isFinal = true
        }

    val idField =
      irClass
        .addField(fieldNameAllocator.newName("id"), irBuiltIns.intType)
        .apply {
          visibility = DescriptorVisibilities.PRIVATE
          isFinal = true
        }

    val constructor =
      irClass
        .addConstructor {
          isPrimary = true
          returnType = irClass.defaultType
        }
        .apply {
          visibility = DescriptorVisibilities.PRIVATE
          val componentParam = addValueParameter("component", graphClass.defaultType)
          val idParam = addValueParameter("id", irBuiltIns.intType)
          val thisReceiver = irClass.thisReceiverOrFail
          body = generateDefaultConstructorBody {
            +irSetField(irGet(thisReceiver), componentField, irGet(componentParam))
            +irSetField(irGet(thisReceiver), idField, irGet(idParam))
          }
        }

    val invokeFunction =
      irClass
        .addFunction("invoke", irBuiltIns.anyNType)
        .apply {
          visibility = DescriptorVisibilities.PUBLIC
          setDispatchReceiver(irClass.thisReceiverOrFail.copyTo(this))
          overriddenSymbols = listOf(metroSymbols.providerInvoke)
        }

    return SwitchingProviderClass(irClass, componentField, idField, constructor, invokeFunction)
  }

  private fun IrBuilderWithScope.castToAny(expression: IrExpression): IrExpression {
    val targetType = context.irBuiltIns.anyNType
    return castExpression(expression, targetType)
  }

  private fun IrBuilderWithScope.castToProvider(
    expression: IrExpression,
    type: IrType,
  ): IrExpression {
    val targetType = metroSymbols.metroProvider.typeWith(type)
    return castExpression(expression, targetType)
  }

  private fun IrBuilderWithScope.castExpression(
    expression: IrExpression,
    targetType: IrType,
  ): IrExpression {
    if (expression.type == targetType) return expression
    return IrTypeOperatorCallImpl(
      UNDEFINED_OFFSET,
      UNDEFINED_OFFSET,
      targetType,
      IrTypeOperator.CAST,
      targetType,
      expression,
    )
  }
}

  private fun IrBinding.isSwitchingCandidate(): Boolean {
    return when (this) {
      is IrBinding.ConstructorInjected,
      is IrBinding.MembersInjected,
      is IrBinding.GraphDependency,
      is IrBinding.Dynamic -> false
      else -> true
    }
  }

