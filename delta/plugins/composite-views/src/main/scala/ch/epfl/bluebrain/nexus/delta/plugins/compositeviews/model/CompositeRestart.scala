package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import scala.annotation.nowarn

/**
  * Possible restart strategies for a composite view
  */
sealed trait CompositeRestart extends Product with Serializable {

  /**
    * The project of the composite view
    */
  def project: ProjectRef

  /**
    * Id of the composite view
    */
  def id: Iri

  /**
    * the instant the user performed the action
    */
  def instant: Instant

  /**
    * the user who performed the action
    */
  def subject: Subject
}

object CompositeRestart {

  val entityType: EntityType = EntityType("composite-restart")

  /**
    * Restarts the view indexing process. It does not delete the created indices/namespaces but it overrides the
    * graphs/documents when going through the log.
    */
  final case class FullRestart(project: ProjectRef, id: Iri, instant: Instant, subject: Subject)
      extends CompositeRestart

  /**
    * Restarts indexing process for all targets while keeping the sources (and the intermediate Sparql space) progress
    */
  final case class FullRebuild(project: ProjectRef, id: Iri, instant: Instant, subject: Subject)
      extends CompositeRestart

  object FullRebuild {

    /**
      * Generates a full rebuild eve
      * @param project
      *   The project of the composite view
      * @param id
      *   The id of the composite view
      */
    def auto(project: ProjectRef, id: Iri): FullRebuild = FullRebuild(project, id, Instant.EPOCH, Anonymous)
  }

  /**
    * Restarts indexing process for the provided target while keeping the sources (and the intermediate Sparql space)
    * progress
    * @param target
    *   the projection to restart
    */
  final case class PartialRebuild(project: ProjectRef, id: Iri, target: Iri, instant: Instant, subject: Subject)
      extends CompositeRestart

  @nowarn("cat=unused")
  implicit val compositeRestartCodec: Codec.AsObject[CompositeRestart] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration = Serializer.circeConfiguration
    deriveConfiguredCodec[CompositeRestart]
  }

}
