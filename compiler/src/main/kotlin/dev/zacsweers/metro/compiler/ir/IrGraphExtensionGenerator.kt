// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.isGeneratedGraph
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.name.ClassId

internal class IrGraphExtensionGenerator(
  context: IrMetroContext,
  private val contributionMerger: IrContributionMerger,
  private val bindingContainerResolver: IrBindingContainerResolver,
  private val parentGraph: IrClass,
  private val dependencyGraphNodesByClass: (ClassId) -> DependencyGraphNode?,
) : IrMetroContext by context {

  private val nameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val generatedClassesCache = mutableMapOf<CacheKey, IrClass>()

  private data class CacheKey(val typeKey: IrTypeKey, val parentGraph: ClassId)

  fun getOrBuildGraphExtensionImpl(
    typeKey: IrTypeKey,
    parentGraph: IrClass,
    contributedAccessor: MetroSimpleFunction,
    parentTracer: Tracer,
  ): IrClass {
    return generatedClassesCache.getOrPut(CacheKey(typeKey, parentGraph.classIdOrFail)) {
      val sourceSamFunction =
        contributedAccessor.ir
          .overriddenSymbolsSequence()
          .firstOrNull {
            it.owner.parentAsClass.isAnnotatedWithAny(
              metroSymbols.classIds.graphExtensionFactoryAnnotations
            )
          }
          ?.owner ?: contributedAccessor.ir

      val parent = sourceSamFunction.parentClassOrNull ?: reportCompilerBug("No parent class found")
      val isFactorySAM =
        parent.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionFactoryAnnotations)
      if (isFactorySAM) {
        generateImplFromFactory(sourceSamFunction, parentTracer, typeKey)
      } else {
        val returnType = contributedAccessor.ir.returnType.rawType()
        val returnIsGraphExtensionFactory =
          returnType.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionFactoryAnnotations)
        val returnIsGraphExtension =
          returnType.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionAnnotations)
        if (returnIsGraphExtensionFactory) {
          val samFunction =
            returnType.singleAbstractFunction().apply {
              remapTypes(sourceSamFunction.typeRemapperFor(contributedAccessor.ir.returnType))
            }
          generateImplFromFactory(samFunction, parentTracer, typeKey)
        } else if (returnIsGraphExtension) {
          // Simple case with no creator
          generateImpl(returnType, creatorFunction = null, typeKey)
        } else {
          reportCompilerBug("Not a graph extension: ${returnType.kotlinFqName}")
        }
      }
    }
  }

  private fun generateImplFromFactory(
    factoryFunction: IrSimpleFunction,
    parentTracer: Tracer,
    typeKey: IrTypeKey,
  ): IrClass {
    val sourceFactory = factoryFunction.parentAsClass
    val sourceGraph = sourceFactory.parentAsClass
    return parentTracer.traceNested("Generate graph extension ${sourceGraph.name}") {
      generateImpl(sourceGraph = sourceGraph, creatorFunction = factoryFunction, typeKey = typeKey)
    }
  }

  private fun generateFactoryImplForCreator(
    graphImpl: IrClass,
    ctor: IrConstructor,
    creatorFunction: IrSimpleFunction,
    parentGraph: IrClass,
  ): IrClass {
    val factoryInterface = creatorFunction.parentAsClass

    // Create the factory implementation as a nested class
    val factoryImpl =
      pluginContext.irFactory
        .buildClass {
          name = "${factoryInterface.name}Impl".asName()
          kind = ClassKind.CLASS
          visibility = DescriptorVisibilities.PRIVATE
          origin = Origins.Default
        }
        .apply {
          this.superTypes = listOf(factoryInterface.defaultType)
          this.typeParameters = copyTypeParametersFrom(factoryInterface)
          this.createThisReceiverParameter()
          graphImpl.addChild(this)
          this.addFakeOverrides(irTypeSystemContext)
        }

    val constructor =
      factoryImpl
        .addConstructor {
          visibility = DescriptorVisibilities.PUBLIC
          isPrimary = true
          this.returnType = factoryImpl.defaultType
        }
        .apply {
          addValueParameter("parentInstance", parentGraph.defaultType)
          body = generateDefaultConstructorBody()
        }

    val paramsToFields = assignConstructorParamsToFields(constructor, factoryImpl)

    // Implement the SAM method
    val samFunction = factoryImpl.singleAbstractFunction()
    samFunction.finalizeFakeOverride(factoryImpl.thisReceiverOrFail)

    // Implement the factory SAM to create the extension
    samFunction.body =
      createIrBuilder(samFunction.symbol).run {
        irExprBodySafe(
          samFunction.symbol,
          irCallConstructor(ctor.symbol, emptyList()).apply {
            // Firstc arg is always the graph instance
            arguments[0] =
              irGetField(
                irGet(samFunction.dispatchReceiverParameter!!),
                paramsToFields.values.first(),
              )
            for (i in 0 until samFunction.regularParameters.size) {
              arguments[i + 1] = irGet(samFunction.regularParameters[i])
            }
          },
        )
      }

    return factoryImpl
  }

  private fun generateImpl(
    sourceGraph: IrClass,
    creatorFunction: IrSimpleFunction?,
    typeKey: IrTypeKey,
  ): IrClass {
    val graphExtensionAnno =
      sourceGraph.annotationsIn(metroSymbols.classIds.graphExtensionAnnotations).firstOrNull()
    val extensionAnno =
      graphExtensionAnno
        ?: reportCompilerBug("Expected @GraphExtension on ${sourceGraph.kotlinFqName}")

    val contributions = contributionMerger.computeContributions(extensionAnno)

    // Source is a `@GraphExtension`-annotated class, we want to generate a header impl class
    val graphImpl =
      pluginContext.irFactory
        .buildClass {
          // Ensure a unique name
          name =
            nameAllocator
              .newName("${sourceGraph.name.asString().capitalizeUS()}${Symbols.StringNames.IMPL}")
              .asName()
          origin = Origins.GeneratedGraphExtension
          kind = ClassKind.CLASS
          // Explicitly set isInner = false to make this a nested class, not an inner class
          // This prevents Kotlin from generating synthetic accessors for parent fields
          isInner = false
        }
        .apply {
          // Create this receiver for the extension itself (not outer class receiver)
          createThisReceiverParameter()

          // Capture the class's thisReceiver for use in constructor body
          // Nested classes don't have dispatchReceiverParameter, only thisReceiver
          val classThisReceiver = thisReceiver!!

          // Add a @DependencyGraph(...) annotation
          // TODO dedupe with dynamic graph gen
          annotations +=
            buildAnnotation(symbol, metroSymbols.metroDependencyGraphAnnotationConstructor) {
              annotation ->
              // scope
              extensionAnno.scopeClassOrNull()?.let {
                annotation.arguments[0] = kClassReference(it.symbol)
              }

              // additionalScopes
              extensionAnno.additionalScopes().copyToIrVararg()?.let {
                annotation.arguments[1] = it
              }

              // excludes
              extensionAnno.excludedClasses().copyToIrVararg()?.let { annotation.arguments[2] = it }

              // bindingContainers
              val allContainers = buildSet {
                val declaredContainers =
                  extensionAnno
                    .bindingContainerClasses(includeModulesArg = options.enableDaggerRuntimeInterop)
                    .map { it.classType.rawType() }
                addAll(declaredContainers)
                contributions?.let { addAll(it.bindingContainers.values) }
              }
              allContainers
                .let(bindingContainerResolver::resolveAllBindingContainersCached)
                .toIrVararg()
                ?.let { annotation.arguments[3] = it }
            }

          superTypes += sourceGraph.defaultType

          // Add only non-binding-container contributions as supertypes
          contributions?.let { superTypes += it.supertypes }

          // Collect all ancestors from parent up to root
          // This matches Dagger's approach of storing direct references to all ancestor components
          val ancestorsList = mutableListOf<AncestorInfo>()

          // Add immediate parent
          var currentAncestor: IrClass? = parentGraph
          var ancestorDepth = 0

          while (currentAncestor != null) {
            val fieldName = when {
              ancestorDepth == 0 -> "parent"  // Immediate parent
              currentAncestor.origin == Origins.MetroGraphDeclaration -> "rootGraph"  // Root component
              else -> "${currentAncestor.name.asString().decapitalizeUS().removeSuffix("Impl")}Instance"
            }

            val ancestorField = addField(
              fieldName.asName(),
              currentAncestor.defaultType,
              DescriptorVisibilities.PRIVATE,
            )

            // Look up the dependency graph node to get the SOURCE typeKey
            // This is critical for matching - parentKey uses node.typeKey (source interface)
            // not IrTypeKey(metroGraph class)
            val ancestorNode = dependencyGraphNodesByClass(currentAncestor.classIdOrFail)
            val sourceTypeKey = ancestorNode?.typeKey ?: IrTypeKey(currentAncestor)

            ancestorsList.add(AncestorInfo(
              componentClass = currentAncestor,
              sourceTypeKey = sourceTypeKey,
              field = ancestorField,
              name = fieldName
            ))

            // Move to next ancestor
            if (currentAncestor.origin == Origins.MetroGraphDeclaration) {
              // Reached root, stop
              break
            } else if (currentAncestor.origin == Origins.GeneratedGraphExtension) {
              // Get this ancestor's parent from its generatedGraphExtensionData
              val ancestorParentField = currentAncestor.generatedGraphExtensionData?.parentField
              currentAncestor = ancestorParentField?.type?.let {
                (it as? org.jetbrains.kotlin.ir.types.IrSimpleType)?.classifier?.owner as? IrClass
              }
            } else {
              // Unknown origin, stop
              break
            }

            ancestorDepth++
          }

          // For backwards compatibility, keep references to parent and rootGraph
          val parentField = ancestorsList.firstOrNull()?.field
          val rootGraphField = ancestorsList.lastOrNull()?.field
          val rootGraph = ancestorsList.lastOrNull()?.componentClass ?: parentGraph

          val ctor =
            addConstructor {
                isPrimary = true
                origin = Origins.Default
                // This will be finalized in IrGraphGenerator
                isFakeOverride = true
              }

              .apply {
                // Add constructor parameters for all ancestors
                // Store parameters in same order as ancestorsList for assignment
                val ancestorParams = ancestorsList.map { ancestor ->
                  addValueParameter(
                    ancestor.name,
                    ancestor.componentClass.defaultType,
                    origin = Origins.ParentComponentParameter,
                  )
                }

                // Copy over any creator params
                creatorFunction?.let {
                  for (param in it.regularParameters) {
                    addValueParameter(param.name, param.type).apply {
                      this.copyAnnotationsFrom(param)
                    }
                  }
                }

                // Generate constructor body with assignment of all ancestor parameters to fields
                body = this.generateDefaultConstructorBody {
                  // Assign each ancestor parameter to its corresponding field
                  for ((ancestor, param) in ancestorsList.zip(ancestorParams)) {
                    +irSetField(
                      receiver = irGet(classThisReceiver),
                      field = ancestor.field,
                      value = irGet(param)
                    )
                  }
                }
              }

          // Must be added to the parent before we generate a factory impl
          parentGraph.addChild(this)

          // If there's an extension, generate it into this impl
          val factoryImpl =
            creatorFunction?.let { factory ->
              // Don't need to do this if the parent implements the factory
              if (parentGraph.implements(factory.parentAsClass.classIdOrFail)) return@let null
              generateFactoryImplForCreator(
                graphImpl = this,
                ctor = ctor,
                creatorFunction = factory,
                parentGraph = this@IrGraphExtensionGenerator.parentGraph,
              )
            }

          generatedGraphExtensionData =
            GeneratedGraphExtensionData(
              typeKey = typeKey,
              factoryImpl = factoryImpl,
              parentField = parentField,
              rootGraphField = rootGraphField,
              rootGraphClass = rootGraph,  // Store the actual root class
              ancestors = ancestorsList,  // Store all ancestors for expression generation
            )
        }

    graphImpl.addFakeOverrides(irTypeSystemContext)

    return graphImpl
  }
}

/**
 * Information about an ancestor component.
 *
 * @param componentClass The generated metro graph implementation class (e.g., $$MetroGraph)
 * @param sourceTypeKey The source component's typeKey (e.g., IrTypeKey(ApplicationComponent))
 * @param field The field in the current component that stores reference to this ancestor
 * @param name The field name (e.g., "parent", "rootGraph", "activityComponentInstance")
 */
internal data class AncestorInfo(
  val componentClass: IrClass,
  val sourceTypeKey: IrTypeKey,  // Source component typeKey for matching
  val field: IrField,
  val name: String,
)

internal class GeneratedGraphExtensionData(
  val typeKey: IrTypeKey,
  val factoryImpl: IrClass? = null,
  val parentField: IrField? = null, // Field storing the immediate parent component reference
  val rootGraphField: IrField? = null, // Field storing the root graph reference (for shard access)
  val rootGraphClass: IrClass? = null, // The actual root graph class (for determining root in nested extensions)
  val ancestors: List<AncestorInfo> = emptyList(), // All ancestors from parent to root
)

internal var IrClass.generatedGraphExtensionData: GeneratedGraphExtensionData? by
  irAttribute(copyByDefault = false)
