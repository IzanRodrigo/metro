// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirMemberGenerationExtensionBase
import dev.zacsweers.metro.compiler.sharding.ShardingConstants
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * FIR generator for shard classes and the SwitchingProvider.
 *
 * This generator creates the class skeletons during the FIR phase, which are then
 * populated with implementation details during the IR phase.
 *
 * Generated structures:
 * 1. Shard classes (Shard1, Shard2, etc.) as nested classes
 * 2. SwitchingProvider class for centralized provider logic
 */
internal class ShardFirGenerator(session: FirSession) : MetroFirMemberGenerationExtensionBase(session) {

  companion object {
    private const val SWITCHING_PROVIDER_NAME = "SwitchingProvider"
    private const val SHARD_PREFIX = "Shard"
  }

  override fun isRelevant(classSymbol: FirClassSymbol<*>): Boolean {
    // Only generate for @DependencyGraph interfaces that have been transformed
    return classSymbol.hasAnnotation(Symbols.ClassIds.DependencyGraph, session) &&
      classSymbol.hasOrigin(Keys.MetroGraphDeclaration)
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    // The SwitchingProvider will have an invoke() method
    return if (classSymbol.hasOrigin(Keys.SwitchingProviderDeclaration)) {
      setOf(Symbols.Names.invoke)
    } else {
      emptySet()
    }
  }

  override fun getNestedClassifierNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (!classSymbol.hasOrigin(Keys.MetroGraphDeclaration)) {
      return emptySet()
    }

    // For the main graph class, generate:
    // 1. SwitchingProvider class
    // 2. ShardN classes based on sharding plan
    val names = mutableSetOf<Name>()

    // Add SwitchingProvider
    names.add(Name.identifier(SWITCHING_PROVIDER_NAME))

    // Check if sharding is enabled and add shard classes
    // The actual number of shards will be determined in IR phase
    // For now, we'll generate placeholders that can be populated later
    val shardingEnabled = getShardingEnabled(classSymbol)
    if (shardingEnabled) {
      // Generate a reasonable number of shard class skeletons
      // The actual used ones will be populated in IR
      for (i in 1..ShardingConstants.MAX_EXPECTED_SHARDS) {
        names.add(Name.identifier("$SHARD_PREFIX$i"))
      }
    }

    return names
  }

  override fun generateNestedClassLikeDeclaration(
    classSymbol: FirClassSymbol<*>,
    name: Name,
    context: MemberGenerationContext,
  ): FirClassLikeDeclaration? {
    when {
      name.asString() == SWITCHING_PROVIDER_NAME -> {
        // Generate SwitchingProvider class
        return createNestedClass(
          owner = classSymbol,
          name = name,
          key = Keys.SwitchingProviderDeclaration,
          classKind = ClassKind.CLASS,
        ) {
          visibility = Visibilities.Private
          modality = Modality.FINAL

          // Add type parameter <T>
          typeParameter(Name.identifier("T"))

          // Set supertype to Provider<T>
          superType(
            typeId(Symbols.ClassIds.MetroProvider).withArguments(
              typeParameter(0)
            )
          )
        }
      }

      name.asString().startsWith(SHARD_PREFIX) -> {
        // Generate shard class skeleton
        return createNestedClass(
          owner = classSymbol,
          name = name,
          key = Keys.ShardClassDeclaration,
          classKind = ClassKind.CLASS,
        ) {
          visibility = Visibilities.Internal
          modality = Modality.FINAL
        }
      }

      else -> return null
    }
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val owner = context.owner

    return when {
      owner.hasOrigin(Keys.SwitchingProviderDeclaration) -> {
        // Generate constructor for SwitchingProvider(graph, id)
        val constructor = createConstructor(
          owner,
          Keys.Default,
          isPrimary = true,
          generateDelegatedNoArgConstructorCall = false,
        ) {
          visibility = Visibilities.Public

          // Add graph parameter (parent component type)
          val parentClass = owner.requireContainingClassSymbol()
          valueParameter(
            name = Name.identifier("graph"),
            key = Keys.RegularParameter,
            type = parentClass.defaultType
          )

          // Add id parameter (Int)
          valueParameter(
            name = Name.identifier("id"),
            key = Keys.RegularParameter,
            type = session.builtinTypes.intType.type
          )
        }
        listOf(constructor.symbol)
      }

      owner.hasOrigin(Keys.ShardClassDeclaration) -> {
        // Generate constructor for Shard(graph, ...modules)
        val constructor = createConstructor(
          owner,
          Keys.Default,
          isPrimary = true,
          generateDelegatedNoArgConstructorCall = true,
        ) {
          visibility = Visibilities.Public

          // Add graph parameter (parent component type)
          val parentClass = owner.requireContainingClassSymbol()
          valueParameter(
            name = Name.identifier("graph"),
            key = Keys.RegularParameter,
            type = parentClass.defaultType
          )

          // Module parameters will be added in IR phase based on actual requirements
        }
        listOf(constructor.symbol)
      }

      else -> emptyList()
    }
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val owner = context?.owner ?: return emptyList()

    if (owner.hasOrigin(Keys.SwitchingProviderDeclaration) && callableId.callableName == Symbols.Names.invoke) {
      // Generate invoke() method for SwitchingProvider
      val typeParam = owner.typeParameterSymbols.firstOrNull()
        ?: error("SwitchingProvider missing type parameter")

      val function = createMemberFunction(
        owner,
        Keys.Default,
        Symbols.Names.invoke,
        returnType = typeParam.resolvedBounds.first().coneType,
      ) {
        status {
          isOverride = true
          isOperator = true
        }
        modality = Modality.OPEN
        visibility = Visibilities.Public
      }

      return listOf(function.symbol)
    }

    return emptyList()
  }

  private fun getShardingEnabled(classSymbol: FirClassSymbol<*>): Boolean {
    // Check if sharding is enabled via compiler options or annotations
    // This is a simplified check; actual logic may be more complex
    return true // For now, assume sharding is enabled if we're generating a graph
  }
}