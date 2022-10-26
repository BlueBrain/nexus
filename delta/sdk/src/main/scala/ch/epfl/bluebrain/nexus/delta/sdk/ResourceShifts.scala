package ch.epfl.bluebrain.nexus.delta.sdk

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityCheck
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import com.typesafe.scalalogging.Logger
import io.circe.Json
import monix.bio.{IO, Task, UIO}

trait ResourceShifts {

  def fetch(reference: ResourceRef, project: ProjectRef): UIO[Option[JsonLdContent[_, _]]]

  def decodeGraphResource(fetchContext: ProjectRef => UIO[ProjectContext]): (EntityType, Json) => Task[GraphResource]

}

object ResourceShifts {

  private val logger: Logger = Logger[ResourceShifts]

  private case class NoShiftAvailable(entityType: EntityType)
      extends Exception(s"No shift is available for entity type $entityType")

  def apply(shifts: Set[ResourceShift[_, _, _]], xas: Transactors)(implicit
      cr: RemoteContextResolution
  ): ResourceShifts = new ResourceShifts {
    private val shiftsMap = shifts.map { encoder => encoder.entityType -> encoder }.toMap

    private def findShift(entityType: EntityType): UIO[ResourceShift[_, _, _]] = IO
      .fromOption(
        shiftsMap.get(entityType),
        NoShiftAvailable(entityType)
      )
      .hideErrors

    override def fetch(reference: ResourceRef, project: ProjectRef): UIO[Option[JsonLdContent[_, _]]] =
      for {
        entityType <- EntityCheck.findType(reference.iri, project, xas)
        shift      <- entityType.traverse(findShift)
        resource   <- shift.flatTraverse(_.toJsonLdContent(reference, project))
      } yield resource

    override def decodeGraphResource(
        fetchContext: ProjectRef => UIO[ProjectContext]
    ): (EntityType, Json) => Task[GraphResource] = { (entityType: EntityType, json: Json) =>
      {
        for {
          shift  <- findShift(entityType)
          result <- shift.toGraphResource(json, fetchContext)
        } yield result
      }.tapError { err =>
        UIO.delay(logger.error(s"Entity of type '$entityType' could not be decoded as a graph resource", err))
      }
    }
  }
}
