package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesPracticeRoutes.SchemaInput._
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesPracticeRoutes.{GenerateSchema, GenerationInput}
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources.{write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.NexusSource.DecodingOption
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{NexusSource, ResourcesPractice}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.{Decoder, Json}
import monix.bio.IO
import monix.execution.Scheduler

import scala.annotation.nowarn

/**
  * The resource practice routes allowing to do read-only operations on resource
  */
final class ResourcesPracticeRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    generateSchema: GenerateSchema,
    resourcesPractice: ResourcesPractice,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    decodingOption: DecodingOption
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import schemeDirectives._
  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      concat(validateRoute, practiceRoute)
    }

  private def validateRoute: Route =
    pathPrefix("resources") {
      extractCaller { implicit caller =>
        resolveProjectRef.apply { project =>
          authorizeFor(project, Write).apply {
            (get & idSegment & idSegmentRef & pathPrefix("validate") & pathEndOrSingleSlash) { (schema, id) =>
              val schemaOpt = underscoreToOption(schema)
              emit(
                resourcesPractice.validate(id, project, schemaOpt).leftWiden[ResourceRejection]
              )
            }
          }
        }
      }
    }

  private def practiceRoute: Route =
    (get & pathPrefix("practice") & pathPrefix("resources")) {
      extractCaller { implicit caller =>
        (resolveProjectRef & pathEndOrSingleSlash) { project =>
          authorizeFor(project, Write).apply {
            (entity(as[GenerationInput])) { input =>
              generate(project, input)
            }
          }
        }
      }
    }

  // Call the generate method matching the schema input
  private def generate(project: ProjectRef, input: GenerationInput)(implicit caller: Caller) =
    input.schema match {
      case ExistingSchema(schemaId) =>
        emit(resourcesPractice.generate(project, schemaId, input.resource).flatMap(_.asJson))
      case NewSchema(schemaSource)  =>
        emit(
          generateSchema(project, schemaSource, caller).flatMap { schema =>
            resourcesPractice.generate(project, schema, input.resource).flatMap(_.asJson)
          }
        )
    }

}

object ResourcesPracticeRoutes {

  type GenerateSchema = (ProjectRef, Json, Caller) => IO[SchemaRejection, SchemaResource]

  sealed private[routes] trait SchemaInput extends Product

  private[routes] object SchemaInput {

    // Validate the generated resource with an existing schema
    final case class ExistingSchema(id: IdSegment) extends SchemaInput

    // Validate the generated resource with the new schema bundled in the request
    final case class NewSchema(json: Json) extends SchemaInput

    implicit val schemaInputDecoder: Decoder[SchemaInput] =
      Decoder.instance { hc =>
        val value          = hc.value
        val existingSchema = value.asString.map { s => ExistingSchema(IdSegment(s)) }
        val newSchema      = NewSchema(value)
        Right(existingSchema.getOrElse(newSchema))
      }
  }

  private val noSchema = ExistingSchema(IriSegment(schemas.resources))

  final private[routes] case class GenerationInput(schema: SchemaInput = noSchema, resource: NexusSource)

  private[routes] object GenerationInput {
    @nowarn("cat=unused")
    implicit def generationInputDecoder(implicit decodingOption: DecodingOption): Decoder[GenerationInput] = {
      implicit val configuration: Configuration             = Configuration.default.withDefaults
      implicit val nexusSourceDecoder: Decoder[NexusSource] = NexusSource.nexusSourceDecoder
      deriveConfiguredDecoder[GenerationInput]
    }
  }

  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      schemas: Schemas,
      resourcesPractice: ResourcesPractice,
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      decodingOption: DecodingOption
  ): ResourcesPracticeRoutes =
    new ResourcesPracticeRoutes(
      identities,
      aclCheck,
      (project, source, caller) => schemas.createDryRun(project, source)(caller).toBIO[SchemaRejection],
      resourcesPractice,
      schemeDirectives
    )
}
