package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.persistenceid.PersistenceIdCheck
import com.typesafe.scalalogging.Logger
import monix.bio.IO

class ResourceIdCheck(idCheck: PersistenceIdCheck, dbModuleTypes: Set[EntityType]) {

  implicit private val logger: Logger = Logger[ResourceIdCheck]

  /**
    * Checks whether an id is available (does not already exist) across different resource types
    *
    * @param project  the project
    * @param id       the id
    * @param onExists the value to return in the error channel when the resource already exists
    * @tparam R the error type
    * @return () on the regular channel if the passed id does not already exist. Return ''onExists'' on the error channel otherwise
    */
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

object ResourceIdCheck {
  type IdAvailability[R] = (ProjectRef, Iri) => IO[R, Unit]
}
