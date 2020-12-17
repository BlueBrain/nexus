package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection.InvalidSchemaResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import monix.bio.IO
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.immutable.VectorMap

class SchemaImportsSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with OptionValues
    with CirceLiteral {

  implicit private val cr: RemoteContextResolution =
    RemoteContextResolution.fixed(contexts.shacl -> jsonContentOf("contexts/shacl.json"))

  private val alice                                = User("alice", Label.unsafe("wonderland"))
  implicit val aliceCaller: Caller                 = Caller(alice, Set(alice))

  "A SchemaImports" should {
    val neuroshapes       = "https://neuroshapes.org"
    val parcellationlabel = iri"$neuroshapes/dash/parcellationlabel"
    val json              = jsonContentOf("schemas/parcellationlabel.json")
    val projectRef        = ProjectGen.project("org", "proj").ref

    val schemaMap   = Map(
      iri"$neuroshapes/commons/entity"        -> jsonContentOf("schemas/entity.json"),
      iri"$neuroshapes/commons/identifier"    -> jsonContentOf("schemas/identifier.json"),
      iri"$neuroshapes/commons/license"       -> jsonContentOf("schemas/license.json"),
      iri"$neuroshapes/commons/propertyvalue" -> jsonContentOf("schemas/propertyvalue.json")
    ).map { case (iri, json) => iri -> SchemaGen.schema(iri, projectRef, json) }

    // format: off
    val resourceMap                          = VectorMap(
      iri"$neuroshapes/commons/vocabulary" -> jsonContentOf("schemas/vocabulary.json"),
      iri"$neuroshapes/wrong/vocabulary"   -> jsonContentOf("schemas/vocabulary.json").replace("owl:Ontology", "owl:Other")
    ).map { case (iri, json) => iri -> ResourceGen.resource(iri, projectRef, json) }
    // format: on

    val errorReport = ResourceResolutionReport()

    val fetchSchema: Resolve[Schema]     = {
      case (ref, `projectRef`, _) => IO.fromOption(schemaMap.get(ref.iri), errorReport)
      case (_, _, _)              => IO.raiseError(errorReport)
    }
    val fetchResource: Resolve[Resource] = {
      case (ref, `projectRef`, _) => IO.fromOption(resourceMap.get(ref.iri), errorReport)
      case (_, _, _)              => IO.raiseError(errorReport)
    }

    val imports = new SchemaImports(fetchSchema, fetchResource)

    "resolve all the imports" in {
      val expanded = ExpandedJsonLd(json).accepted
      val result   = imports.resolve(parcellationlabel, projectRef, expanded).accepted
      result.unwrap.toSet shouldEqual
        (resourceMap.take(1).values.map(_.expanded).toSet ++ schemaMap.values.map(_.expanded).toSet + expanded)
    }

    "fail to resolve an import if it is not found" in {
      val other        = iri"$neuroshapes/other"
      val other2       = iri"$neuroshapes/other2"
      val parcellation = json deepMerge json"""{"imports": ["$neuroshapes/commons/entity", "$other", "$other2"]}"""
      val expanded     = ExpandedJsonLd(parcellation).accepted

      imports.resolve(parcellationlabel, projectRef, expanded).rejected shouldEqual
        InvalidSchemaResolution(
          parcellationlabel,
          schemaImports = Map(ResourceRef(other) -> errorReport, ResourceRef(other2) -> errorReport),
          resourceImports = Map(ResourceRef(other) -> errorReport, ResourceRef(other2) -> errorReport),
          nonOntologyResources = Set.empty
        )
    }

    "fail to resolve an import if it is a resource without owl:Ontology type" in {
      val wrong        = iri"$neuroshapes/wrong/vocabulary"
      val parcellation = json deepMerge json"""{"imports": ["$neuroshapes/commons/entity", "$wrong"]}"""
      val expanded     = ExpandedJsonLd(parcellation).accepted

      imports.resolve(parcellationlabel, projectRef, expanded).rejected shouldEqual
        InvalidSchemaResolution(
          parcellationlabel,
          schemaImports = Map(ResourceRef(wrong) -> errorReport),
          resourceImports = Map.empty,
          nonOntologyResources = Set(ResourceRef(wrong))
        )
    }
  }
}
