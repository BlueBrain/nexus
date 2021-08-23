package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.persistenceid.PersistenceIdCheck
import com.typesafe.scalalogging.Logger
import monix.bio.IO

trait ResourceIdCheck {
  def isAvailableOr[R](project: ProjectRef, id: Iri)(onExists: => R): IO[R, Unit]
}

object ResourceIdCheck {
  final def apply(idCheck: PersistenceIdCheck, dbModuleTypes: Set[EntityType]): ResourceIdCheck =
    new ResourceIdCheck {

      implicit private val logger: Logger = Logger[ResourceIdCheck]

      def isAvailableOr[R](project: ProjectRef, id: Iri)(onExists: => R): IO[R, Unit] = {
        val persistenceIds = dbModuleTypes.map { moduleType =>
          s"${moduleType.value}-${UrlUtils.encode(project.toString)}_${UrlUtils.encode(id.toString)}"
        }
        idCheck
          .existsAny(persistenceIds.toSeq: _*)
          .logAndDiscardErrors(s"checking id duplicates for project '$project' and id '$id' failed")
          .flatMap {
            case true  => IO.raiseError(onExists)
            case false => IO.unit
          }
      }
    }

  final def alwaysAvailable: ResourceIdCheck = new ResourceIdCheck {
    override def isAvailableOr[R](project: ProjectRef, id: Iri)(onExists: => R): IO[R, Unit] = IO.unit
  }

  type IdAvailability[R] = (ProjectRef, Iri) => IO[R, Unit]
}
