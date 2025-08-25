/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irComposite
import org.jetbrains.kotlin.ir.builders.irCallOp
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fields
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * Represents the specification for creating a field in a shard class.
 */
internal data class ShardFieldSpec(
  val name: String,
  val type: IrType,
  val typeKey: IrTypeKey,
  val initializer: FieldInitializer,
  val binding: IrBinding,
  val accessType: IrGraphExpressionGenerator.AccessType,
  val isScoped: Boolean,
  val isProviderType: Boolean,
  val visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE
)

/**
 * Generates helper classes that store provider fields for large dependency graphs.
 * This implements the graph sharding strategy to avoid "class too large" errors.
 */
internal class ProviderClassGenerator(
  metroContext: IrMetroContext,
  private val mainGraphClass: IrClass,
  shardIndex: Int,
  private val fieldSpecs: List<ShardFieldSpec> = emptyList(),
  private val bindingGraph: IrBindingGraph,
  private val node: DependencyGraphNode,
) : IrMetroContext by metroContext {

  private val className = Name.identifier("${mainGraphClass.name.asString()}Providers$shardIndex")
  
  data class GeneratedShard(
    val shardClass: IrClass,
    val fields: Map<IrTypeKey, IrField>,
    val getterMethods: Map<IrTypeKey, IrSimpleFunction>
  )
  
  fun generate(): GeneratedShard {
    val createdFields = mutableListOf<IrField>()
    val fieldToSpec = mutableMapOf<IrField, ShardFieldSpec>()
    val getterMethods = mutableMapOf<IrTypeKey, IrSimpleFunction>()
    
    val shardClass = irFactory.buildClass {
      name = className
      visibility = DescriptorVisibilities.INTERNAL
      modality = Modality.FINAL
      kind = ClassKind.CLASS
      origin = Origins.GeneratedGraphShard
    }.apply {
      // Position it inside the main graph class
      parent = mainGraphClass
      
      // Important: Create the thisReceiver before trying to access defaultType
      createThisReceiverParameter()
      
      // Now we can safely access the main graph's defaultType
      val mainGraphType = mainGraphClass.defaultType
      
      // Add primary constructor with graph parameter
      val ctor = addConstructor {
        isPrimary = true
        visibility = DescriptorVisibilities.INTERNAL
        returnType = this@apply.defaultType
      }
      
      val graphParam = ctor.addValueParameter {
        name = Name.identifier("graph")
        type = mainGraphType
      }
      
      // Store graph parameter as a field for cross-shard access
      val graphField = addField {
        name = Name.identifier("graph")
        type = mainGraphType
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
      }
      
      // Create all the fields that belong to this shard
      // These are nullable backing fields that will be initialized lazily
      fieldSpecs.forEach { spec ->
        // Create a nullable backing field (prefixed with _)
        val backingFieldName = "_${spec.name}"
        val field = addField {
          name = Name.identifier(backingFieldName)
          type = spec.type.makeNullable()
          visibility = DescriptorVisibilities.PRIVATE
          isFinal = false // Must be non-final for lazy initialization
        }
        createdFields.add(field)
        fieldToSpec[field] = spec
        
        // Create a getter method for lazy initialization
        val getterMethod = addFunction {
          name = Name.identifier("get${spec.name.capitalize()}")
          returnType = spec.type
          visibility = DescriptorVisibilities.INTERNAL
        }.apply {
          val getterFunction = this
          // Set the dispatch receiver for this method
          setDispatchReceiver(thisReceiver!!.copyTo(this, type = thisReceiver!!.type))
          // Create the method body with lazy initialization
          body = createIrBuilder(symbol).irBlockBody {
            // Simple null check and initialization - no double-check for now
            // to keep the implementation simpler and avoid synchronization issues
            val resultVar = irTemporary(
              value = irGetField(irGet(dispatchReceiverParameter!!), field),
              nameHint = "result",
              isMutable = true
            )
            
            +irIfThen(
              type = irBuiltIns.unitType,
              condition = irEquals(irGet(resultVar), irNull()),
              thenPart = irComposite {
                // Get the graph reference from the field
                val graphReference = irGetField(irGet(dispatchReceiverParameter!!), graphField)
                
                // Create a temporary variable to hold the graph
                val graphVar = irTemporary(graphReference, nameHint = "graph")
                
                // Generate the initialization code for this field
                // We need to create an expression generator that uses the graph reference
                // The graph field in the shard contains a reference to the main graph instance
                // which has all the fields and context needed for dependency resolution
                
                // Generate the initialization expression using the field initializer
                // The field initializer is a lambda that takes a receiver parameter and generates the expression
                // We need to create a synthetic parameter to represent the graph reference
                val syntheticGraphParam = dispatchReceiverParameter!!.copyTo(
                  getterFunction,
                  type = mainGraphType,
                  name = Name.identifier("graphParam")
                )
                
                // Create a builder context and invoke the initializer
                val initializedValue = createIrBuilder(symbol).run {
                  // The initializer expects a parameter representing 'this' (the graph)
                  // We'll generate the expression and then transform it to use our graph variable
                  val generatedExpr = spec.initializer(this, syntheticGraphParam, spec.typeKey)
                  
                  // Transform the expression to replace references to the synthetic parameter
                  // with references to our actual graph variable
                  generatedExpr.transform(object : IrElementTransformerVoid() {
                    override fun visitGetValue(expression: IrGetValue): IrExpression {
                      return if (expression.symbol == syntheticGraphParam.symbol) {
                        irGet(graphVar)
                      } else {
                        super.visitGetValue(expression)
                      }
                    }
                  }, null)
                }
                
                // Store in both the temporary variable and the backing field
                +irSet(resultVar, initializedValue)
                +irSetField(
                  irGet(dispatchReceiverParameter!!),
                  field,
                  irGet(resultVar)
                )
              }
            )
            
            // Return the non-null result (use !! operator which translates to checkNotNull)
            // Since we just initialized it, it should never be null at this point
            +irReturn(
              irGet(resultVar)
            )
          }
        }
        getterMethods[spec.typeKey] = getterMethod
      }
      
      // Initialize the graph field in constructor
      ctor.body = createIrBuilder(ctor.symbol).irBlockBody {
        // Call the super constructor (Any's constructor)
        +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)
        
        // Initialize the instance
        +IrInstanceInitializerCallImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          this@apply.symbol,
          this@apply.defaultType,
        )
        
        // Set the field value from the constructor parameter
        // In a constructor, we use irGet with the thisReceiver of the class being constructed
        +irSetField(
          irGet(this@apply.thisReceiver!!),
          graphField,
          irGet(graphParam)
        )
        
        // DO NOT initialize shard fields in the constructor
        // This causes issues with circular dependencies and incorrect receiver contexts
        // The fields are marked as non-final and will be initialized lazily on first access
        // TODO: Implement proper lazy initialization using double-check pattern or similar
      }
      
      // Add this class as a nested class inside the main graph
      mainGraphClass.addChild(this)
    }
    
    // Create field mapping
    val fieldMap = fieldSpecs.zip(createdFields).associate { (spec, field) -> spec.typeKey to field }
    
    return GeneratedShard(shardClass, fieldMap, getterMethods)
  }
}

/**
 * Specification for a provider class shard.
 */
internal data class ProviderClassSpec(
  val shardIndex: Int,
  val bindings: List<IrBinding>,
  val className: Name,
)

/**
 * Distributes bindings across multiple provider classes to avoid class size limits.
 */
internal class FieldDistributor(
  private val maxFieldsPerShard: Int,
) {
  
  /**
   * Distributes the given bindings across multiple provider class specifications.
   * Each class will have at most [maxFieldsPerShard] fields.
   * Returns empty list if sharding is not needed (bindings fit in main class).
   */
  fun distribute(
    bindings: List<IrBinding>,
    mainGraphClass: IrClass,
  ): List<ProviderClassSpec> {
    if (maxFieldsPerShard == Int.MAX_VALUE || bindings.size <= maxFieldsPerShard) {
      // Sharding disabled or not needed - can fit in main class
      return emptyList()
    }
    
    return bindings
      .chunked(maxFieldsPerShard)
      .mapIndexed { index, chunk ->
        ProviderClassSpec(
          shardIndex = index + 1,
          bindings = chunk,
          className = Name.identifier("${mainGraphClass.name.asString()}Providers${index + 1}")
        )
      }
  }
  
  /**
   * Determines if sharding is needed based on the number of bindings.
   */
  fun shouldShard(bindingCount: Int): Boolean {
    return maxFieldsPerShard != Int.MAX_VALUE && bindingCount > maxFieldsPerShard
  }
}