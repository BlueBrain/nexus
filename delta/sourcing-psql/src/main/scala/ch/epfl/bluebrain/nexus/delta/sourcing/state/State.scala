package ch.epfl.bluebrain.nexus.delta.sourcing.state

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}

import java.time.Instant

sealed trait State extends Product with Serializable {

  /**
    * @return
    *   the current state revision
    */
  def rev: Int

  /**
    * @return
    *   the current deprecation status
    */
  def deprecated: Boolean

  /**
    * @return
    *   the instant when the state was created
    */
  def createdAt: Instant

  /**
    * @return
    *   the subject that state the resource
    */
  def createdBy: Subject

  /**
    * @return
    *   the instant when the state was last updated
    */
  def updatedAt: Instant

  /**
    * @return
    *   the subject that last updated the state
    */
  def updatedBy: Subject

  /**
    * @return
    *   the schema reference that the state conforms to
    */
  def schema: ResourceRef

  /**
    * @return
    *   the collection of known types
    */
  def types: Set[Iri]

}

object State {

  trait GlobalState extends State

  trait ScopedState extends State {

    /**
      * @return
      *   the project where the event belongs
      */
    def project: ProjectRef

    /**
      * @return
      *   the parent organization label
      */
    def organization: Label = project.organization

  }

}
