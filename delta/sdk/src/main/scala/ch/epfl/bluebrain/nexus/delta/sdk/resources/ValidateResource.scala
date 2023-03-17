package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.{ShaclEngine, ValidationReport}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidateResource.ValidationResult
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidateResource.ValidationResult.{NoValidation, Validated}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidJsonLdFormat, InvalidResource, InvalidSchemaRejection, ReservedResourceId, ResourceShaclEngineRejection, SchemaIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{Encoder, JsonObject}
import monix.bio.IO

object ValidateResource {
  sealed trait ValidationResult {
    def schema: ResourceRef.Revision
    def project: ProjectRef
  }

  object ValidationResult {
    implicit val resourceRejectionEncoder: Encoder.AsObject[ValidationResult] =
      Encoder.AsObject.instance[ValidationResult] { r =>
        val tpe = ClassUtils.simpleName(r)
        val obj = JsonObject(
          keywords.tpe := tpe,
          "schema"     := r.schema,
          "project"    := r.project
        )
        r match {
          case NoValidation(_)         => obj
          case Validated(_, _, report) =>
            obj
              .add("report", report.asJson)
              .add("@context", "https://bluebrain.github.io/nexus/contexts/shacl-20170720.json".asJson)
        }
      }

    implicit val resourceRejectionJsonLdEncoder: JsonLdEncoder[ValidationResult] =
      JsonLdEncoder.computeFromCirce(ContextValue(contexts.validation))
    final case class NoValidation(project: ProjectRef) extends ValidationResult {
      override def schema: ResourceRef.Revision = ResourceRef.Revision(schemas.resources, 1)
    }

    case class Validated(project: ProjectRef, schema: ResourceRef.Revision, report: ValidationReport)
        extends ValidationResult
  }
}

trait ValidateResource {
  def apply(
      projectRef: ProjectRef,
      schemaRef: ResourceRef,
      caller: Caller,
      resourceId: Iri,
      expanded: ExpandedJsonLd
  ): IO[ResourceRejection, ValidationResult]
}

final class ValidateResourceImpl(resourceResolution: ResourceResolution[Schema])(implicit
    jsonLdApi: JsonLdApi
) extends ValidateResource {
  private def toGraph(id: Iri, expanded: ExpandedJsonLd): IO[ResourceRejection, Graph] =
    IO.fromEither(expanded.toGraph).mapError(err => InvalidJsonLdFormat(Some(id), err))

  override def apply(
      projectRef: ProjectRef,
      schemaRef: ResourceRef,
      caller: Caller,
      resourceId: Iri,
      expanded: ExpandedJsonLd
  ): IO[ResourceRejection, ValidationResult] =
    if (isUnconstrained(schemaRef))
      assertNotReservedId(resourceId) >>
        toGraph(resourceId, expanded) >>
        IO.pure(NoValidation(projectRef))
    else
      for {
        _      <- assertNotReservedId(resourceId)
        graph  <- toGraph(resourceId, expanded)
        schema <- resolveSchema(resourceResolution, projectRef, schemaRef, caller)
        report <- shaclValidate(schemaRef, resourceId, schema, graph)
        _      <- IO.raiseWhen(!report.isValid())(InvalidResource(resourceId, schemaRef, report, expanded))
      } yield Validated(schema.value.project, ResourceRef.Revision(schema.id, schema.rev), report)

  private def shaclValidate(schemaRef: ResourceRef, resourceId: Iri, schema: ResourceF[Schema], graph: Graph)(implicit
      jsonLdApi: JsonLdApi
  ) = {
    ShaclEngine(graph ++ schema.value.ontologies, schema.value.shapes, reportDetails = true, validateShapes = false)
      .mapError(ResourceShaclEngineRejection(resourceId, schemaRef, _))
  }

  private def assertNotDeprecated(schema: ResourceF[Schema]) = {
    IO.raiseWhen(schema.deprecated)(SchemaIsDeprecated(schema.value.id))
  }

  private def assertNotReservedId(resourceId: Iri) = {
    IO.raiseWhen(resourceId.startsWith(contexts.base))(ReservedResourceId(resourceId))
  }

  private def isUnconstrained(schemaRef: ResourceRef) = {
    schemaRef == Latest(schemas.resources) || schemaRef == ResourceRef.Revision(schemas.resources, 1)
  }

  private def resolveSchema(
      resourceResolution: ResourceResolution[Schema],
      projectRef: ProjectRef,
      schemaRef: ResourceRef,
      caller: Caller
  ) = {
    resourceResolution
      .resolve(schemaRef, projectRef)(caller)
      .mapError(InvalidSchemaRejection(schemaRef, projectRef, _))
      .tapEval(schema => assertNotDeprecated(schema))
  }
}
