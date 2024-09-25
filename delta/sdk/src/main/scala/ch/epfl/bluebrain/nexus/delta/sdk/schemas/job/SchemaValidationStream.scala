package ch.epfl.bluebrain.nexus.delta.sdk.schemas.job

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidateResource
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.FetchSchema
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef, SuccessElemStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.FailureReason

/**
  * Streams the latest version of resources from a project and revalidate them with the latest version of the schema
  * they are currently validated with.
  *   - Deprecated resources are skipped
  *   - Resources not validated with a schema are skipped too
  */
trait SchemaValidationStream {

  def apply(project: ProjectRef, offset: Offset): ElemStream[Unit]
}

object SchemaValidationStream {

  def apply(
      resourceStream: (ProjectRef, Offset) => SuccessElemStream[ResourceState],
      fetchSchema: FetchSchema,
      validateResource: ValidateResource
  ): SchemaValidationStream = new SchemaValidationStream {

    private def validateSingle(resource: ResourceState) =
      for {
        jsonld <- IO.fromEither(resource.toAssembly)
        schema <- fetchSchema(Latest(resource.schema.iri), resource.schemaProject)
        _      <- validateResource(jsonld, schema).adaptErr { case r: ResourceRejection =>
                    FailureReason("ValidateSchema", r.reason, r)
                  }
      } yield (Some(()))

    override def apply(project: ProjectRef, offset: Offset): ElemStream[Unit] =
      resourceStream(project, offset).evalMap {
        _.evalMapFilter {
          case r if r.deprecated                      => IO.none
          case r if r.schema.iri == schemas.resources => IO.none
          case r                                      => validateSingle(r)
        }
      }
  }
}
