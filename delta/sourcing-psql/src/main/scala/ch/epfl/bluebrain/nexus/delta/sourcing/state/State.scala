package ch.epfl.bluebrain.nexus.delta.sourcing.state

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}

import java.time.Instant

/**
  * Super type for all states
  */
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

  /**
    * State for entities that are transversal to all projects
    */
  trait GlobalState extends State

  /**
    * State for entities living inside a project
    */
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

  /**
    * State for immutable entities living inside a project and that will be purged after a given period
    */
  trait EphemeralState extends ScopedState {

    def deprecated: Boolean = false

    def rev: Int = 1

    def updatedAt: Instant = createdAt

    def updatedBy: Subject = createdBy
  }

}
