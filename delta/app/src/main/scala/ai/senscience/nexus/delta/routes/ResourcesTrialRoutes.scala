package ai.senscience.nexus.delta.routes

import ai.senscience.nexus.delta.routes.ResourcesTrialRoutes.SchemaInput.{ExistingSchema, NewSchema}
import ai.senscience.nexus.delta.routes.ResourcesTrialRoutes.{GenerateSchema, GenerationInput}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources.write as Write
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{NexusSource, ResourcesTrial}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.{Decoder, Json}

/**
  * The resource trial routes allowing to do read-only operations on resources
  */
final class ResourcesTrialRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    generateSchema: GenerateSchema,
    resourcesTrial: ResourcesTrial
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      concat(validateRoute, generateRoute)
    }

  private def validateRoute: Route =
    pathPrefix("resources") {
      extractCaller { implicit caller =>
        projectRef.apply { project =>
          (idSegment & idSegmentRef & pathPrefix("validate") & pathEndOrSingleSlash & get) { (schema, id) =>
            authorizeFor(project, Write).apply {
              val schemaOpt = underscoreToOption(schema)
              emit(
                resourcesTrial
                  .validate(id, project, schemaOpt)
                  .attemptNarrow[ResourceRejection]
              )
            }
          }
        }
      }
    }

  private def generateRoute: Route =
    (pathPrefix("trial") & pathPrefix("resources") & post) {
      extractCaller { implicit caller =>
        (projectRef & pathEndOrSingleSlash) { project =>
          authorizeFor(project, Write).apply {
            entity(as[GenerationInput]) { input =>
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
        emit(
          resourcesTrial
            .generate(project, schemaId, input.resource)
            .flatMap(_.asJson)
        )
      case NewSchema(schemaSource)  =>
        emit(
          generateSchema(project, schemaSource, caller)
            .flatMap { schema =>
              resourcesTrial
                .generate(project, schema, input.resource)
                .flatMap(_.asJson)
            }
            .attemptNarrow[SchemaRejection]
        )
    }
}

object ResourcesTrialRoutes {

  type GenerateSchema = (ProjectRef, Json, Caller) => IO[SchemaResource]

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

    implicit val generationInputDecoder: Decoder[GenerationInput] = {
      implicit val configuration: Configuration = Configuration.default.withDefaults
      deriveConfiguredDecoder[GenerationInput]
    }
  }

  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      schemas: Schemas,
      resourcesTrial: ResourcesTrial
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): ResourcesTrialRoutes =
    new ResourcesTrialRoutes(
      identities,
      aclCheck,
      (project, source, caller) => schemas.createDryRun(project, source)(caller),
      resourcesTrial
    )
}
