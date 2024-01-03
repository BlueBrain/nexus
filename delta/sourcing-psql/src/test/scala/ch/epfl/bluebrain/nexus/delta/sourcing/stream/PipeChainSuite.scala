package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.AllowedViewTypes
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DiscardMetadata, FilterBySchema, FilterByType, FilterDeprecated}
import munit.FunSuite

class PipeChainSuite extends FunSuite {

  private val resourceSchemas = AllowedViewTypes.fromIri(nxv + "MySchema")
  private val resourceTypes   = AllowedViewTypes.fromIri(nxv + "MyType")

  private def chain(
      schemas: AllowedViewTypes,
      types: AllowedViewTypes,
      includeMetadata: Boolean,
      includeDeprecated: Boolean
  ) =
    PipeChain(schemas, types, includeMetadata, includeDeprecated).map(_.pipes.map(_._1))

  test("Do not create a pipechain if there is no constraint") {
    assertEquals(PipeChain(AllowedViewTypes.All, AllowedViewTypes.All, true, true), None)
  }

  test("Create a pipechain with schemas") {
    assertEquals(
      chain(resourceSchemas, AllowedViewTypes.All, true, true),
      Some(NonEmptyChain.one(FilterBySchema.ref))
    )
  }

  test("Create a pipechain with schemas and types") {
    assertEquals(
      chain(resourceSchemas, resourceTypes, true, true),
      Some(NonEmptyChain(FilterBySchema.ref, FilterByType.ref))
    )
  }

  test("Create a pipechain with schemas and types, exclude metadata") {
    assertEquals(
      chain(resourceSchemas, resourceTypes, false, true),
      Some(NonEmptyChain(FilterBySchema.ref, FilterByType.ref, DiscardMetadata.ref))
    )
  }

  test("Create a pipechain with schemas and types, exclude metadata and deprecated") {
    assertEquals(
      chain(resourceSchemas, resourceTypes, false, false),
      Some(NonEmptyChain(FilterBySchema.ref, FilterByType.ref, FilterDeprecated.ref, DiscardMetadata.ref))
    )
  }
}
