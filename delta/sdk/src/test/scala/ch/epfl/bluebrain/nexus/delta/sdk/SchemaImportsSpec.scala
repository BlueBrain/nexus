package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaImports.Fetch
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceType.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection.{ProjectNotFound, ResourceNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection.{InvalidSchemaResolution, SchemaNotFound, WrappedResolverResolutionRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceRef, ResourceType}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import monix.bio.IO
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SchemaImportsSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with OptionValues
    with CirceLiteral {

  implicit private val cr: RemoteContextResolution =
    RemoteContextResolution.fixed(contexts.shacl -> jsonContentOf("contexts/shacl.json"))

  "A SchemaImports" should {
    val neuroshapes       = "https://neuroshapes.org"
    val parcellationlabel = iri"$neuroshapes/dash/parcellationlabel"
    val json              = jsonContentOf("schemas/parcellationlabel.json")
    val projectRef        = ProjectGen.project("org", "proj").ref

    val schemaMap                            = Map(
      iri"$neuroshapes/commons/entity"        -> jsonContentOf("schemas/entity.json"),
      iri"$neuroshapes/commons/identifier"    -> jsonContentOf("schemas/identifier.json"),
      iri"$neuroshapes/commons/license"       -> jsonContentOf("schemas/license.json"),
      iri"$neuroshapes/commons/propertyvalue" -> jsonContentOf("schemas/propertyvalue.json")
    ).map { case (iri, json) => iri -> SchemaGen.schema(iri, projectRef, json) }

    val resourceMap                          = Map(
      iri"$neuroshapes/commons/vocabulary" -> jsonContentOf("schemas/vocabulary.json")
    ).map { case (iri, json) => iri -> ResourceGen.resource(iri, projectRef, json) }

    def notFound(id: Iri, tpe: ResourceType) = WrappedResolverResolutionRejection(ResourceNotFound(id, projectRef, tpe))

    def projectNotFound(projectRef: ProjectRef) = WrappedResolverResolutionRejection(ProjectNotFound(projectRef))

    val fetchSchema: Fetch[Schema]     = {
      case (`projectRef`, ref) => IO.fromOption(schemaMap.get(ref.iri), notFound(ref.iri, SchemaResource))
      case (otherProject, _)   => IO.raiseError(projectNotFound(otherProject))
    }
    val fetchResource: Fetch[Resource] = {
      case (`projectRef`, ref) => IO.fromOption(resourceMap.get(ref.iri), SchemaNotFound(ref.iri, projectRef))
      case (otherProject, _)   => IO.raiseError(projectNotFound(otherProject))
    }

    val imports = new SchemaImports(fetchSchema, fetchResource)

    "resolve all the imports" in {
      val expanded = ExpandedJsonLd(json).accepted
      val result   = imports.resolve(parcellationlabel, projectRef, expanded).accepted
      result.unwrap.toSet shouldEqual
        (resourceMap.values.map(_.expanded).toSet ++ schemaMap.values.map(_.expanded).toSet + expanded)
    }

    "fail to resolve an import if it is not found" in {
      val other        = iri"$neuroshapes/other"
      val other2       = iri"$neuroshapes/other2"
      val parcellation = json deepMerge json"""{"imports": ["$neuroshapes/commons/entity", "$other", "$other2"]}"""
      val expanded     = ExpandedJsonLd(parcellation).accepted

      imports.resolve(parcellationlabel, projectRef, expanded).rejected shouldEqual
        InvalidSchemaResolution(parcellationlabel, Set(ResourceRef(other), ResourceRef(other2)))
    }
  }
}
